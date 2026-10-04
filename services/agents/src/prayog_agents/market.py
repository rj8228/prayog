"""Shared view of the market for all agents: one market-data connection, a local book per symbol."""

import asyncio
import logging

from prayog_sdk import LocalBook, SequenceGap, Settings, market_data

from prayog_agents.quoting import Ema

log = logging.getLogger(__name__)


class Market:
    def __init__(self, settings: Settings, symbols: list[str]) -> None:
        self.settings = settings
        self.symbols = symbols
        self.books = {s: LocalBook(s) for s in symbols}
        self.session = "CLOSED"
        self.fast = {s: Ema(half_life=5) for s in symbols}
        self.slow = {s: Ema(half_life=40) for s in symbols}
        self.trades = dict.fromkeys(symbols, 0)
        self.ready = asyncio.Event()

    @property
    def is_open(self) -> bool:
        return self.session == "OPEN"

    async def run(self) -> None:
        async for message in market_data(self.settings, self.symbols):
            kind = message.get("type")
            if kind == "session":
                self.session = message["state"]
                log.info("session %s", self.session)
                continue
            symbol = message.get("symbol")
            if symbol not in self.books:
                continue
            if kind == "snapshot":
                self.session = message["session"]
            try:
                self.books[symbol].apply(message)
            except SequenceGap as gap:
                log.warning("%s; waiting for the next snapshot", gap)
                continue
            if kind == "trade":
                self.trades[symbol] += 1
                self.fast[symbol].update(message["price"])
                self.slow[symbol].update(message["price"])
            if all(b.seq >= 0 for b in self.books.values()):
                self.ready.set()
