"""A small, readable trading bot: buys dips below the reference price and sells the bounce.

Run it against the local stack (from the repository root)::

    uv run python sdk/python/examples/sample_bot.py --symbol INFY

It shows the three things every Prayog bot does:

1. Keep a local order book from the market-data feed (snapshot + numbered deltas).
2. Track its own position from the private feed (fills), not from guesses.
3. Enforce its own risk limits before every order.

It trades with market orders for simplicity, so it pays the spread on every trade. Read
docs/bots/README.md to
build something better.
"""

import argparse
import asyncio
import logging
from pathlib import Path

from prayog_sdk import LocalBook, PrayogClient, SequenceGap, Settings, market_data, private_updates
from prayog_sdk.cli import load_dotenv
from prayog_sdk.client import PrayogError
from prayog_sdk.models import Fill
from prayog_sdk.money import to_rupees

log = logging.getLogger("sample_bot")


class SampleBot:
    def __init__(
        self, client: PrayogClient, symbol: str, lot: int, max_position: int, edge_bps: int
    ) -> None:
        self.client = client
        self.symbol = symbol
        self.lot = lot
        self.max_position = max_position
        # How far below/above the reference price counts as cheap/expensive, in basis points.
        self.edge_bps = edge_bps
        self.book = LocalBook(symbol)
        self.position = 0
        self.reference = 0

    async def run(self, seconds: float) -> None:
        instruments = {i.symbol: i for i in await self.client.instruments()}
        self.reference = instruments[self.symbol].reference_price
        await self.client.cancel_all()  # start clean
        log.info(
            "trading %s, reference %s, lot %d", self.symbol, to_rupees(self.reference), self.lot
        )
        tasks = [asyncio.create_task(self._track_fills()), asyncio.create_task(self._trade())]
        try:
            await asyncio.wait_for(asyncio.gather(*tasks), timeout=seconds)
        except TimeoutError:
            pass
        finally:
            for task in tasks:
                task.cancel()
            await self.client.cancel_all()
            log.info("done; final position %d", self.position)

    async def _track_fills(self) -> None:
        async for update in private_updates(self.client):
            if isinstance(update, Fill):
                self.position += update.quantity if update.side == "BUY" else -update.quantity
                log.info(
                    "fill %s %d @ %s -> position %d",
                    update.side,
                    update.quantity,
                    to_rupees(update.price),
                    self.position,
                )

    async def _trade(self) -> None:
        async for message in market_data(self.client.settings, [self.symbol]):
            try:
                self.book.apply(message)
            except SequenceGap as gap:
                log.warning("%s; the feed will resend a snapshot", gap)
                continue
            if message["type"] != "book" or self.book.mid is None:
                continue
            cheap = self.reference * (1 - self.edge_bps / 10_000)
            rich = self.reference * (1 + self.edge_bps / 10_000)
            try:
                ask, bid = self.book.best_ask, self.book.best_bid
                room_to_buy = self.position + self.lot <= self.max_position
                room_to_sell = self.position - self.lot >= -self.max_position
                if ask is not None and ask < cheap and room_to_buy:
                    await self._send("BUY")
                elif bid is not None and bid > rich and room_to_sell:
                    await self._send("SELL")
            except PrayogError as e:
                log.warning("order refused: %s", e)
                await asyncio.sleep(1)

    async def _send(self, side: str) -> None:
        result = await self.client.place(self.symbol, side, self.lot)
        log.info(
            "%s %d market -> %s, filled %d", side, self.lot, result.status, result.filled_quantity
        )
        await asyncio.sleep(0.5)  # simple pacing: never more than two orders a second


async def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--symbol", default="INFY")
    parser.add_argument("--lot", type=int, default=5)
    parser.add_argument("--max-position", type=int, default=50)
    parser.add_argument("--edge-bps", type=int, default=20, help="0.20%% from reference by default")
    parser.add_argument("--seconds", type=float, default=300)
    parser.add_argument("--account", default="sample-bot")
    args = parser.parse_args()
    load_dotenv(Path.cwd() / ".env")
    async with PrayogClient(Settings.from_env(account=args.account)) as client:
        await SampleBot(client, args.symbol, args.lot, args.max_position, args.edge_bps).run(
            args.seconds
        )


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(message)s")
    asyncio.run(main())
