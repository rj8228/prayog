"""A local copy of one symbol's order book, kept exact from the market-data feed."""

from dataclasses import dataclass, field


class SequenceGap(Exception):
    """A message was missed (its seq is not last + 1). Resubscribe to get a fresh snapshot."""


@dataclass
class LocalBook:
    """Apply a ``snapshot`` and then every ``book``/``trade`` message in order. Raises
    :class:`SequenceGap` if one
    is missing, so the book is never silently wrong."""

    symbol: str
    seq: int = -1
    bids: dict[int, tuple[int, int]] = field(default_factory=dict)  # price -> (quantity, orders)
    asks: dict[int, tuple[int, int]] = field(default_factory=dict)
    last_price: int = 0

    def apply(self, message: dict) -> None:
        kind = message.get("type")
        if kind == "snapshot":
            self.seq = message["seq"]
            self.bids = {lv["price"]: (lv["quantity"], lv["orders"]) for lv in message["bids"]}
            self.asks = {lv["price"]: (lv["quantity"], lv["orders"]) for lv in message["asks"]}
            if message.get("ticker"):
                self.last_price = message["ticker"]["last"]
            return
        if kind not in ("book", "trade"):
            return
        if self.seq < 0:
            raise SequenceGap(f"{self.symbol}: {kind} before any snapshot")
        if message["seq"] != self.seq + 1:
            raise SequenceGap(f"{self.symbol}: expected seq {self.seq + 1}, got {message['seq']}")
        self.seq = message["seq"]
        if kind == "trade":
            self.last_price = message["price"]
            return
        for change in message["changes"]:
            side = self.bids if change["side"] == "BUY" else self.asks
            if change["quantity"] == 0:
                side.pop(change["price"], None)
            else:
                side[change["price"]] = (change["quantity"], change["orders"])

    @property
    def best_bid(self) -> int | None:
        return max(self.bids) if self.bids else None

    @property
    def best_ask(self) -> int | None:
        return min(self.asks) if self.asks else None

    @property
    def mid(self) -> float | None:
        if self.best_bid is None or self.best_ask is None:
            return None
        return (self.best_bid + self.best_ask) / 2

    def depth(self, side: str, levels: int = 10) -> list[tuple[int, int, int]]:
        """Best-first ``(price, quantity, orders)`` levels for ``"BUY"`` or ``"SELL"``."""
        book = self.bids if side == "BUY" else self.asks
        prices = sorted(book, reverse=(side == "BUY"))[:levels]
        return [(p, *book[p]) for p in prices]
