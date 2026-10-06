"""Post-trade check of a running stack (part of ``make e2e``). Exits 1 if any check fails.

S16/S17 acceptance on the live market:

1. Post-trade has consumed the event stream up to what the exchange published.
2. The ledger is zero-sum: across all accounts, net quantity per symbol is 0, and P&L before
   charges is exactly 0 (every rupee one account made, another lost, at the same marks).
3. A bot buys with one account and sells with another; the trades show up as fills, positions,
   charges and P&L, and both accounts appear on the leaderboard.
4. Chaos: post-trade is stopped, the bot trades while it is down, post-trade starts again and
   catches up; the ledger is still zero-sum and the trade made during the outage is there.
"""

import argparse
import asyncio
import os
import subprocess
import sys
import time
from dataclasses import replace
from pathlib import Path

import httpx
from prayog_sdk import PrayogClient, Settings
from prayog_sdk.cli import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from kafka_check import COMPOSE, Ops, check, results, wait_for  # noqa: E402


def caught_up(ops: Ops) -> bool:
    published = ops.status()["kafka"]["publishedSeq"]
    status = ops.request("GET", "/api/v1/post-trade/status")
    consumed = max(status["lastEventIdByPartition"].values(), default=0)
    return consumed >= published - 50  # a few events may still be in flight


def zero_sum(ops: Ops) -> None:
    status = ops.request("GET", "/api/v1/post-trade/status")
    check(
        status["pnlBeforeCharges"] == 0,
        f"P&L before charges sums to 0 ({status['pnlBeforeCharges']})",
    )
    quantities = status["netQuantityBySymbol"]
    check(
        all(q == 0 for q in quantities.values()),
        f"net quantity per symbol is 0 ({quantities})",
    )
    print(
        f"        {status['trades']} trades, {status['accounts']} accounts, "
        f"charges {status['charges'] / 100:,.2f} rupees"
    )


async def account(api_url: str, client: PrayogClient, label: str, path: str):
    headers = await client.auth_headers()
    headers["X-Prayog-Account"] = label
    async with httpx.AsyncClient(timeout=10) as http:
        response = await http.get(f"{api_url}/api/v1/account/{path}", headers=headers)
        response.raise_for_status()
        return response.json()


async def cross(settings: Settings, symbol: str, quantity: int) -> int:
    """One bot account buys and another sells, both at market against the live book.

    Returns the buyer's first trade id (0 if nothing filled)."""
    buyer = PrayogClient(replace(settings, account="e2e-buyer"))
    seller = PrayogClient(replace(settings, account="e2e-seller"))
    async with buyer, seller:
        bought = await buyer.buy_market(symbol, quantity)
        await seller.sell_market(symbol, quantity)
        return bought.fills[0].trade_id if bought.fills else 0


async def bot_round_trip(settings: Settings, api_url: str, symbol: str) -> None:
    trade_id = await cross(settings, symbol, 3)
    check(trade_id > 0, f"bot bought and sold at market (trade {trade_id})")
    client = PrayogClient(settings)
    async with client:

        async def seen() -> bool:
            fills = await account(api_url, client, "e2e-buyer", "fills?limit=20")
            return any(f["tradeId"] == trade_id for f in fills)

        deadline = time.monotonic() + 30
        while not await seen() and time.monotonic() < deadline:
            await asyncio.sleep(0.5)
        check(await seen(), "the trade reached post-trade as a fill")
        buyer = await account(api_url, client, "e2e-buyer", "pnl")
        seller = await account(api_url, client, "e2e-seller", "pnl")
        position = {p["symbol"]: p for p in buyer["positions"]}.get(symbol, {})
        check(position.get("bought", 0) >= 3, f"buyer's position shows the purchase ({position})")
        check(buyer["charges"] > 0 and seller["charges"] > 0, "both sides paid charges")
        check(
            buyer["rank"] > 0 and seller["rank"] > 0,
            f"both on the leaderboard (#{buyer['rank']}, #{seller['rank']})",
        )
    async with httpx.AsyncClient(timeout=10) as http:
        board = (await http.get(f"{api_url}/api/v1/leaderboard?limit=5")).json()
    check(
        board["accounts"] > 0 and len(board["entries"]) > 0,
        f"leaderboard has {board['accounts']} accounts",
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--outage", type=float, default=5, help="seconds post-trade stays down")
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    api_url = os.environ.get("PRAYOG_API_URL", "http://api.prayog.localhost")
    ops = Ops(
        api_url,
        os.environ.get("PRAYOG_AUTH_URL", "http://auth.prayog.localhost/realms/prayog"),
        os.environ["PRAYOG_OPS_TOOL_SECRET"],
    )
    settings = Settings.from_env()
    symbol = "INFY"

    print("Post-trade ledger")
    check(
        bool(wait_for(lambda: caught_up(ops), 300, 2)),
        "consumed up to the exchange's published events",
    )
    zero_sum(ops)
    asyncio.run(bot_round_trip(settings, api_url, symbol))

    print(f"Post-trade down for {args.outage:.0f} s")
    subprocess.run([*COMPOSE, "stop", "post-trade"], capture_output=True, check=True)
    try:
        trade_id = asyncio.run(cross(settings, symbol, 2))
        check(trade_id > 0, f"trading carried on without post-trade (trade {trade_id})")
        time.sleep(args.outage)
    finally:
        subprocess.run(
            [*COMPOSE, "up", "-d", "--wait", "post-trade"], capture_output=True, check=True
        )
    check(bool(wait_for(lambda: caught_up(ops), 120, 1)), "caught up after the restart")
    zero_sum(ops)

    async def outage_trade_seen() -> bool:
        async with PrayogClient(settings) as client:
            fills = await account(api_url, client, "e2e-buyer", "fills?limit=50")
            return any(f["tradeId"] == trade_id for f in fills)

    check(asyncio.run(outage_trade_seen()), "the trade made during the outage is in the ledger")

    failed = [name for ok, name in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
