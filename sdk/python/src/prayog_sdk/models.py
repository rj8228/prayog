"""Typed views of the exchange's JSON. Prices are integer paise; times are sim time in epoch
microseconds."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field
from pydantic.alias_generators import to_camel


class Model(BaseModel):
    """Accepts the API's camelCase and exposes snake_case attributes."""

    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, extra="ignore")


Side = Literal["BUY", "SELL"]


class FillView(Model):
    trade_id: int
    price: int
    quantity: int


class OrderResult(Model):
    """What the exchange did with a request, after it was journaled.

    ``status``: ``resting`` (on the book, maybe partly filled), ``filled``, ``cancelled`` (nothing
    left open, e.g. a
    market order's unfilled rest), ``modified`` or ``rejected`` (see ``reason``).
    """

    status: str
    order_id: int
    client_order_id: str | None = None
    symbol: str | None = None
    reason: str | None = None
    filled_quantity: int = 0
    leaves_quantity: int = 0
    fills: list[FillView] = Field(default_factory=list)
    input_seq: int = 0
    sim_time: int = 0

    @property
    def ok(self) -> bool:
        return self.status != "rejected"


class OpenOrder(Model):
    order_id: int
    client_order_id: str | None = None
    symbol: str
    side: Side
    price: int
    quantity: int
    leaves_quantity: int
    filled_quantity: int


class Instrument(Model):
    symbol: str
    tick_size: int
    max_order_quantity: int
    reference_price: int
    band_percent: int
    band_low: int
    band_high: int


class SessionInfo(Model):
    state: str
    sim_time: int
    clock_multiplier: int
    open: str
    close: str
    offset: str


class Level(Model):
    price: int
    quantity: int
    orders: int


class TradePrint(Model):
    symbol: str
    seq: int
    trade_id: int
    price: int
    quantity: int
    aggressor: Side
    sim_time: int


class Ticker(Model):
    symbol: str
    reference_price: int
    last: int
    open: int
    high: int
    low: int
    volume: int
    trades: int
    best_bid: int
    best_ask: int


class Snapshot(Model):
    symbol: str
    seq: int
    session: str
    bids: list[Level]
    asks: list[Level]
    trades: list[TradePrint] = Field(default_factory=list)
    ticker: Ticker | None = None


class OrderUpdate(Model):
    """One of your orders changed. ``status``: accepted, rejected, cancelled or modified."""

    status: str
    event_seq: int
    sim_time: int
    order_id: int
    client_order_id: str | None = None
    symbol: str
    side: Side | None = None
    price: int | None = None
    quantity: int | None = None
    leaves_quantity: int | None = None
    reason: str | None = None


class Fill(Model):
    """One of your orders traded. ``side`` is your side; ``aggressor`` is true if your order took
    liquidity."""

    event_seq: int
    sim_time: int
    trade_id: int
    order_id: int
    symbol: str
    side: Side
    price: int
    quantity: int
    aggressor: bool
