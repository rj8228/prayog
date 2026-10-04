"""Pure decision logic for the simulated traders, kept separate so it can be tested without a
network."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Quote:
    side: str  # "BUY" or "SELL"
    level: int  # 0 = best
    price: int  # paise, on the tick, inside the band
    quantity: int


def round_to_tick(price: float, tick: int, side: str) -> int:
    """Bids round down and asks round up, so rounding never makes a quote more aggressive."""
    units = price / tick
    whole = int(units) if side == "BUY" else -int(-units // 1)
    return max(tick, whole * tick)


def maker_quotes(
    fair: float,
    inventory: int,
    *,
    tick: int,
    lot: int,
    half_spread_ticks: int,
    levels: int,
    level_step_ticks: int,
    quote_lots: int,
    skew_ticks_per_lot: float,
    max_inventory_lots: int,
    band_low: int,
    band_high: int,
) -> list[Quote]:
    """Two-sided quotes around ``fair``, shifted against inventory.

    Long inventory moves both sides down (sell more readily, buy less eagerly); short moves them
    up. Past
    ``max_inventory_lots`` the maker stops adding to the position on that side altogether.
    """
    inventory_lots = inventory / lot
    center = fair - skew_ticks_per_lot * inventory_lots * tick
    quotes: list[Quote] = []
    for level in range(levels):
        offset = (half_spread_ticks + level * level_step_ticks) * tick
        bid = round_to_tick(center - offset, tick, "BUY")
        ask = round_to_tick(center + offset, tick, "SELL")
        if ask <= bid:
            ask = bid + tick
        size = quote_lots * lot
        if inventory_lots < max_inventory_lots and band_low <= bid <= band_high:
            quotes.append(Quote("BUY", level, bid, size))
        if inventory_lots > -max_inventory_lots and band_low <= ask <= band_high:
            quotes.append(Quote("SELL", level, ask, size))
    return quotes


@dataclass
class Ema:
    """Exponential moving average with a half-life measured in updates."""

    half_life: float
    value: float | None = None

    def update(self, x: float) -> float:
        alpha = 1 - 0.5 ** (1 / self.half_life)
        self.value = x if self.value is None else self.value + alpha * (x - self.value)
        return self.value


def momentum_signal(fast: float | None, slow: float | None, threshold: float) -> int:
    """+1 (buy) when the fast average is above the slow one by more than ``threshold`` (a
    fraction), -1 when
    below, else 0."""
    if fast is None or slow is None or slow == 0:
        return 0
    gap = (fast - slow) / slow
    if gap > threshold:
        return 1
    if gap < -threshold:
        return -1
    return 0
