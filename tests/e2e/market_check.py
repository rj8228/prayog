"""End-to-end correctness check of a running stack (``make e2e``). Exits 1 if any check fails.

Run with the stack and the simulated traders up. It uses only the public API, like any bot:

1. Feed integrity: rebuild every order book from the market-data WebSocket and require it to equal
the exchange's
   own REST snapshot at the same sequence number.
2. Liquidity: the market maker keeps both sides quoted (bid < ask) most of the time.
3. Activity: every symbol trades during the window.
4. Bot round trip: a resting order, its private ``accepted`` update, a cancel, and a market order
that fills.
"""

import argparse
import asyncio
import json
import sys
import time
from pathlib import Path

from prayog_sdk import LocalBook, PrayogClient, SequenceGap, Settings, market_data, private_updates
from prayog_sdk.cli import load_dotenv
from prayog_sdk.models import Fill, OrderUpdate

DEPTH = 500
results: list[tuple[bool, str]] = []


def check(ok: bool, name: str) -> None:
    results.append((ok, name))
    print(f"  {'ok  ' if ok else 'FAIL'}  {name}", flush=True)


async def watch_market(settings: Settings, symbols: list[str], seconds: float):
    """Consumes the feed for ``seconds``; samples two-sidedness twice a second; returns books and
    stats."""
    books = {s: LocalBook(s) for s in symbols}
    samples = dict.fromkeys(symbols, 0)
    two_sided = dict.fromkeys(symbols, 0)
    trades = dict.fromkeys(symbols, 0)
    gaps = 0
    stream = market_data(settings, symbols, depth=DEPTH)
    deadline = time.monotonic() + seconds
    next_sample = time.monotonic()
    async for message in stream:
        symbol = message.get("symbol")
        if symbol in books:
            try:
                books[symbol].apply(message)
            except SequenceGap:
                gaps += 1
            if message["type"] == "trade":
                trades[symbol] += 1
        now = time.monotonic()
        if now >= next_sample and all(b.seq >= 0 for b in books.values()):
            next_sample = now + 0.5
            for s, b in books.items():
                samples[s] += 1
                if b.best_bid is not None and b.best_ask is not None and b.best_bid < b.best_ask:
                    two_sided[s] += 1
        if now >= deadline:
            break
    return books, stream, samples, two_sided, trades, gaps


async def compare_with_snapshot(client: PrayogClient, books: dict, stream) -> None:
    """Brings each local book to the seq of a fresh REST snapshot and compares them level by
    level."""
    for symbol, book in books.items():
        for _attempt in range(20):
            snapshot = await client.book(symbol, DEPTH)
            # Advance the local book until it reaches the snapshot's seq (the feed may lag a
            # little).
            while book.seq < snapshot.seq:
                message = await anext(stream)
                if message.get("symbol") in books:
                    books[message["symbol"]].apply(message)
            if book.seq == snapshot.seq:
                local_bids = [(p, q, o) for p, q, o in book.depth("BUY", DEPTH)]
                local_asks = [(p, q, o) for p, q, o in book.depth("SELL", DEPTH)]
                remote_bids = [(lv.price, lv.quantity, lv.orders) for lv in snapshot.bids]
                remote_asks = [(lv.price, lv.quantity, lv.orders) for lv in snapshot.asks]
                same = local_bids == remote_bids and local_asks == remote_asks
                check(
                    same,
                    f"{symbol}: book rebuilt from the feed equals the exchange's at seq {book.seq}",
                )
                break
            # The feed got ahead of that snapshot: take a newer one.
        else:
            check(False, f"{symbol}: could not line up the feed with a snapshot")


async def bot_round_trip(settings: Settings, symbol: str) -> None:
    async with PrayogClient(settings) as client:
        updates: list = []

        async def listen() -> None:
            async for update in private_updates(client):
                updates.append(update)

        listener = asyncio.create_task(listen())
        session = await client.session()
        if session.state != "OPEN":
            check(False, f"session is {session.state}: cannot test trading")
            listener.cancel()
            return
        band_low = next(i for i in await client.instruments() if i.symbol == symbol).band_low
        # Wait until the private feed is really live: a probe order's acceptance must arrive on it.
        # A fixed sleep was not enough under load (the token fetch and handshake can take seconds).
        deadline = asyncio.get_running_loop().time() + 15
        while asyncio.get_running_loop().time() < deadline:
            probe = await client.buy_limit(symbol, 1, band_low)
            await client.cancel(probe.order_id)
            await asyncio.sleep(0.5)
            if any(isinstance(u, OrderUpdate) and u.order_id == probe.order_id for u in updates):
                break
        resting = await client.buy_limit(symbol, 1, band_low)
        check(resting.status == "resting", f"bot limit order rests (order {resting.order_id})")
        listed = any(o.order_id == resting.order_id for o in await client.open_orders())
        check(listed, "bot order is listed in its open orders")
        cancelled = await client.cancel(resting.order_id)
        check(cancelled.status == "cancelled", "bot order cancels")
        filled = await client.buy_market(symbol, 1)
        check(
            filled.filled_quantity == 1,
            f"bot market order fills against the book ({filled.status})",
        )
        await asyncio.sleep(1.5)
        listener.cancel()
        statuses = {(u.order_id, u.status) for u in updates if isinstance(u, OrderUpdate)}
        check((resting.order_id, "accepted") in statuses, "private feed reported the acceptance")
        check((resting.order_id, "cancelled") in statuses, "private feed reported the cancel")
        fills = [u for u in updates if isinstance(u, Fill) and u.order_id == filled.order_id]
        check(len(fills) == 1, "private feed reported the fill")


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--seconds", type=float, default=30)
    parser.add_argument("--min-two-sided", type=float, default=0.95)
    args = parser.parse_args()
    load_dotenv(Path.cwd() / ".env")
    settings = Settings.from_env(account="e2e")

    async with PrayogClient(settings) as client:
        symbols = [i.symbol for i in await client.instruments()]
        # Warm-up: on a fresh stack the simulated traders need a few seconds to quote and trade.
        for _ in range(60):
            tickers = await client.tickers()
            if all(t.trades > 0 and t.best_bid and t.best_ask for t in tickers):
                break
            await asyncio.sleep(1)
        print(f"Watching {', '.join(symbols)} for {args.seconds:.0f} s", flush=True)
        books, stream, samples, two_sided, trades, gaps = await watch_market(
            settings, symbols, args.seconds
        )
        print("Feed integrity")
        check(gaps == 0, f"no sequence gaps on the feed ({gaps})")
        await compare_with_snapshot(client, books, stream)
        print("Liquidity and activity")
        for s in symbols:
            ratio = two_sided[s] / samples[s] if samples[s] else 0
            check(ratio >= args.min_two_sided, f"{s}: two-sided {ratio:.0%} of the time")
            check(trades[s] > 0, f"{s}: {trades[s]} trades")
        tickers = {t.symbol: t for t in await client.tickers()}
        print(json.dumps({s: tickers[s].last for s in symbols}))
    print("Bot round trip")
    await bot_round_trip(settings, symbols[0])

    failed = [name for ok, name in results if not ok]
    print(f"\n{len(results) - len(failed)}/{len(results)} checks passed")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
