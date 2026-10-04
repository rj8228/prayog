"""``prayog``: trade and inspect the market from a terminal.

Examples (prices in rupees here; the API itself uses integer paise)::

    uv run prayog book INFY
    uv run prayog buy INFY 10 --price 1495.50
    uv run prayog sell TCS 5            # market order
    uv run prayog orders
    uv run prayog cancel 42
    uv run prayog watch INFY            # live ladder and trades, Ctrl-C to stop

It authenticates as a bot client (default: the demo bot, secret from the repository's .env).
``--account`` picks a
separate account under the same client.
"""

import argparse
import asyncio
import os
import sys
from pathlib import Path

from prayog_sdk.book import LocalBook, SequenceGap
from prayog_sdk.client import PrayogClient, PrayogError
from prayog_sdk.config import Settings
from prayog_sdk.money import rupees, to_rupees
from prayog_sdk.streams import market_data, private_updates


def load_dotenv(path: Path) -> None:
    """Minimal .env reader: KEY=VALUE lines; existing environment variables win."""
    if not path.is_file():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            os.environ.setdefault(key.strip(), value.strip())


def _parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="prayog", description="Trade on the Prayog exchange from a terminal."
    )
    p.add_argument("--account", help="account label under your client (default: main)")
    sub = p.add_subparsers(dest="command", required=True)
    sub.add_parser("instruments", help="listed symbols with tick size and price band")
    sub.add_parser("session", help="market state and sim time")
    sub.add_parser("tickers", help="last price and volume for every symbol")
    sub.add_parser("me", help="which account you are trading as")
    b = sub.add_parser("book", help="order book snapshot")
    b.add_argument("symbol")
    b.add_argument("--depth", type=int, default=10)
    t = sub.add_parser("trades", help="recent trades")
    t.add_argument("symbol")
    t.add_argument("--limit", type=int, default=20)
    for side in ("buy", "sell"):
        o = sub.add_parser(side, help=f"{side} (limit with --price, else market)")
        o.add_argument("symbol")
        o.add_argument("quantity", type=int)
        o.add_argument("--price", help="limit price in rupees, e.g. 1495.50")
    sub.add_parser("orders", help="your open orders")
    c = sub.add_parser("cancel", help="cancel one open order")
    c.add_argument("order_id", type=int)
    sub.add_parser("cancel-all", help="cancel all your open orders")
    m = sub.add_parser("modify", help="change price and/or total quantity")
    m.add_argument("order_id", type=int)
    m.add_argument("--price", required=True, help="new price in rupees")
    m.add_argument(
        "--quantity", type=int, required=True, help="new TOTAL quantity, including filled"
    )
    w = sub.add_parser("watch", help="live ladder and trades")
    w.add_argument("symbol")
    sub.add_parser("fills", help="stream your order updates and fills")
    return p


def _print_result(r) -> None:
    line = f"{r.status:<9} order {r.order_id}  filled {r.filled_quantity}  open {r.leaves_quantity}"
    if r.reason:
        line += f"  ({r.reason})"
    print(line)
    for f in r.fills:
        print(f"  fill {f.quantity} @ {to_rupees(f.price)}  trade {f.trade_id}")


def _print_ladder(book: LocalBook, levels: int = 10) -> None:
    asks = book.depth("SELL", levels)
    bids = book.depth("BUY", levels)
    print(f"{book.symbol}  last {to_rupees(book.last_price) if book.last_price else '-'}")
    print(f"{'orders':>7} {'qty':>8} {'ASK':>10}")
    for price, qty, orders in reversed(asks):
        print(f"{orders:>7} {qty:>8} {to_rupees(price):>10}")
    print("-" * 28)
    for price, qty, orders in bids:
        print(f"{orders:>7} {qty:>8} {to_rupees(price):>10}  BID")


async def _run(args: argparse.Namespace) -> int:
    overrides = {"account": args.account} if args.account else {}
    settings = Settings.from_env(**overrides)
    async with PrayogClient(settings) as client:
        cmd = args.command
        if cmd == "instruments":
            for i in await client.instruments():
                print(
                    f"{i.symbol:<10} tick {to_rupees(i.tick_size):>6}  ref "
                    f"{to_rupees(i.reference_price):>9}  "
                    f"band {to_rupees(i.band_low)}..{to_rupees(i.band_high)}  max qty "
                    f"{i.max_order_quantity}"
                )
        elif cmd == "session":
            s = await client.session()
            print(
                f"{s.state}  sim time {s.sim_time}  x{s.clock_multiplier}  hours "
                f"{s.open}-{s.close} {s.offset}"
            )
        elif cmd == "tickers":
            for t in await client.tickers():
                last = to_rupees(t.last) if t.last else "-"
                print(
                    f"{t.symbol:<10} last {last:>9}  vol {t.volume:>8}  trades {t.trades:>6}  "
                    f"bid {to_rupees(t.best_bid):>9}  ask {to_rupees(t.best_ask):>9}"
                )
        elif cmd == "me":
            print(await client.me())
        elif cmd == "book":
            snap = await client.book(args.symbol, args.depth)
            book = LocalBook(args.symbol)
            book.apply(snap.model_dump(by_alias=True) | {"type": "snapshot"})
            _print_ladder(book, args.depth)
        elif cmd == "trades":
            for tr in await client.trades(args.symbol, args.limit):
                print(
                    f"{tr.trade_id:>8}  {tr.quantity:>6} @ {to_rupees(tr.price):>9}  {tr.aggressor}"
                )
        elif cmd in ("buy", "sell"):
            price = rupees(args.price) if args.price else None
            _print_result(await client.place(args.symbol, cmd.upper(), args.quantity, price=price))
        elif cmd == "orders":
            orders = await client.open_orders()
            if not orders:
                print("no open orders")
            for o in orders:
                print(
                    f"{o.order_id:>8}  {o.side:<4} {o.symbol:<10} "
                    f"{o.leaves_quantity:>6}/{o.quantity:<6} "
                    f"@ {to_rupees(o.price)}"
                )
        elif cmd == "cancel":
            _print_result(await client.cancel(args.order_id))
        elif cmd == "cancel-all":
            print(f"cancelled {await client.cancel_all()} order(s)")
        elif cmd == "modify":
            _print_result(await client.modify(args.order_id, rupees(args.price), args.quantity))
        elif cmd == "watch":
            book = LocalBook(args.symbol)
            async for message in market_data(settings, [args.symbol]):
                try:
                    book.apply(message)
                except SequenceGap as gap:
                    print(f"gap ({gap}); waiting for a fresh snapshot")
                    continue
                if message["type"] == "trade":
                    print(
                        f"TRADE {message['quantity']} @ {to_rupees(message['price'])} "
                        f"({message['aggressor']})"
                    )
                elif message["type"] in ("snapshot", "book"):
                    print("\033[2J\033[H", end="")  # clear the terminal
                    _print_ladder(book)
        elif cmd == "fills":
            async for update in private_updates(client):
                print(update.model_dump_json())
    return 0


def main() -> None:
    load_dotenv(Path.cwd() / ".env")
    args = _parser().parse_args()
    try:
        sys.exit(asyncio.run(_run(args)))
    except PrayogError as e:
        print(f"error: {e}", file=sys.stderr)
        sys.exit(1)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
