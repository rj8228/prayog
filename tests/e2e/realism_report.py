"""Realism report (S14): collects trades and spreads from the running market for each scenario and
writes docs/realism.md. Uses only the public market-data feed plus ops calls to switch scenario.

    uv run python tests/e2e/realism_report.py --seconds 600   # per scenario; about 20 minutes

Returns are taken over ``--bucket`` seconds of sim time (default 10 s), pooled over all symbols.
"""

import argparse
import asyncio
import contextlib
import os
import sys
import time
from datetime import date
from pathlib import Path

import numpy as np
from prayog_agents.realism import bucket_prices, log_returns, return_stats, spread_stats
from prayog_sdk import LocalBook, SequenceGap, Settings, market_data
from prayog_sdk.cli import load_dotenv

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(Path(__file__).parent))
from kafka_check import Ops  # noqa: E402

SYMBOLS = ["HDFCBANK", "INFY", "RELIANCE", "TCS"]
TICK = 5


async def collect(settings: Settings, seconds: float):
    """Trades as (simTime, price) per symbol, and (bid, ask) samples twice a second."""
    books = {s: LocalBook(s) for s in SYMBOLS}
    trades: dict[str, list[tuple[int, int]]] = {s: [] for s in SYMBOLS}
    spreads: dict[str, list[tuple[int | None, int | None]]] = {s: [] for s in SYMBOLS}
    deadline = time.monotonic() + seconds
    next_sample = time.monotonic()
    async for message in market_data(settings, SYMBOLS, depth=5):
        symbol = message.get("symbol")
        if symbol in books:
            with contextlib.suppress(SequenceGap):  # the stream resyncs itself
                books[symbol].apply(message)
            if message["type"] == "trade":
                trades[symbol].append((message["simTime"], message["price"]))
        now = time.monotonic()
        if now >= next_sample:
            next_sample = now + 0.5
            for s, b in books.items():
                if b.seq >= 0:
                    spreads[s].append((b.best_bid, b.best_ask))
        if now > deadline:
            break
    return trades, spreads


def section(scenario: str, trades, spreads, bucket_seconds: int) -> list[str]:
    returns = np.concatenate(
        [log_returns(bucket_prices(trades[s], bucket_seconds * 1_000_000)) for s in SYMBOLS]
    )
    r = return_stats(returns)
    all_spreads = [x for s in SYMBOLS for x in spreads[s]]
    sp = spread_stats(all_spreads, TICK)
    count = sum(len(trades[s]) for s in SYMBOLS)
    acf = ", ".join(f"{c:+.2f}" for c in r.return_autocorr)
    abs_acf = ", ".join(f"{c:+.2f}" for c in r.abs_return_autocorr)
    return [
        f"## Scenario `{scenario}`",
        "",
        "| Measure | Value |",
        "|---|---|",
        f"| Trades observed | {count:,} |",
        f"| {bucket_seconds}-second returns (pooled, 4 symbols) | {r.count:,} |",
        f"| Return std | {r.std_bps:.2f} bps |",
        f"| Smallest / largest return | {r.min_bps:.1f} / {r.max_bps:.1f} bps |",
        f"| Skew | {r.skew:+.2f} |",
        f"| Excess kurtosis (0 = normal) | {r.excess_kurtosis:+.2f} |",
        f"| Autocorrelation of returns, lags 1-5 | {acf} |",
        f"| Autocorrelation of absolute returns, lags 1-5 | {abs_acf} |",
        f"| Spread: mean / median / p95 | {sp.mean_ticks:.2f} / {sp.median_ticks:.1f} / "
        f"{sp.p95_ticks:.1f} ticks ({sp.mean_bps:.2f} bps) |",
        f"| Two-sided book | {sp.two_sided_share:.1%} of {sp.samples:,} samples |",
        "",
    ]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--seconds", type=float, default=600, help="wall seconds per scenario")
    parser.add_argument("--bucket", type=int, default=10, help="return interval in sim seconds")
    parser.add_argument("--out", default=str(ROOT / "docs/realism.md"))
    args = parser.parse_args()
    load_dotenv(ROOT / ".env")
    api = os.environ.get("PRAYOG_API_URL", "http://api.prayog.localhost")
    ops = Ops(
        api,
        os.environ.get("PRAYOG_AUTH_URL", "http://auth.prayog.localhost/realms/prayog"),
        os.environ["PRAYOG_OPS_TOOL_SECRET"],
    )
    settings = Settings.from_env()
    if ops.status()["session"] != "OPEN":
        ops.request("POST", "/api/v1/ops/session", {"state": "OPEN"})
    ops.request("PUT", "/api/v1/ops/clock", {"multiplier": 1})
    lines = [
        "# Realism report (S14)",
        "",
        f"Generated {date.today().isoformat()} by `uv run python tests/e2e/realism_report.py "
        f"--seconds {args.seconds:.0f} --bucket {args.bucket}` on the local stack (clock at 1x).",
        "",
        "Stylised facts of real markets to compare against: returns with fat tails "
        "(excess kurtosis above 0), little autocorrelation in returns, positive autocorrelation "
        "in absolute returns (volatility clustering), and a tight, always two-sided spread while "
        "a market maker is active.",
        "",
    ]
    try:
        for scenario in ["calm", "volatile"]:
            print(f"== {scenario}: collecting for {args.seconds:.0f} s", flush=True)
            ops.request("PUT", "/api/v1/admin/simulation", {"scenario": scenario, "paused": False})
            time.sleep(5)  # let the traders switch
            trades, spreads = asyncio.run(collect(settings, args.seconds))
            part = section(scenario, trades, spreads, args.bucket)
            print("\n".join(part))
            lines += part
    finally:
        ops.request("PUT", "/api/v1/admin/simulation", {"scenario": "calm", "paused": False})
    Path(args.out).write_text("\n".join(lines) + "\n")
    print(f"written to {args.out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
