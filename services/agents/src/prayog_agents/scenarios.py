"""Market scenarios: how the hidden fair value moves and how the simulated traders behave."""

from dataclasses import dataclass


@dataclass(frozen=True)
class Scenario:
    """All numbers per step of ``step_seconds`` of wall time; volatilities are fractions of the
    price.

    The fair value is a mean-reverting random walk with jumps (an Ornstein-Uhlenbeck process in log
    price): it
    wanders, is pulled back toward the reference price at speed ``reversion``, and now and then
    jumps by about
    ``jump_size`` (news).
    """

    name: str
    step_seconds: float
    volatility: float
    reversion: float
    jump_probability: float
    jump_size: float
    # market maker
    half_spread_ticks: int
    levels: int
    level_step_ticks: int
    quote_lots: int
    skew_ticks_per_lot: float
    max_inventory_lots: int
    # takers
    noise_traders: int
    noise_mean_interval_seconds: float
    noise_max_lots: int
    momentum_traders: int
    momentum_threshold: float
    momentum_lots: int
    momentum_max_lots: int


CALM = Scenario(
    name="calm",
    step_seconds=0.5,
    volatility=0.0004,
    reversion=0.002,
    jump_probability=0.0005,
    jump_size=0.004,
    half_spread_ticks=2,
    levels=3,
    level_step_ticks=2,
    quote_lots=4,
    skew_ticks_per_lot=0.5,
    max_inventory_lots=60,
    noise_traders=3,
    noise_mean_interval_seconds=2.0,
    noise_max_lots=3,
    momentum_traders=1,
    momentum_threshold=0.0008,
    momentum_lots=2,
    momentum_max_lots=10,
)

VOLATILE = Scenario(
    name="volatile",
    step_seconds=0.5,
    volatility=0.0012,
    reversion=0.001,
    jump_probability=0.004,
    jump_size=0.012,
    half_spread_ticks=4,
    levels=3,
    level_step_ticks=3,
    quote_lots=3,
    skew_ticks_per_lot=1.0,
    max_inventory_lots=40,
    noise_traders=4,
    noise_mean_interval_seconds=1.2,
    noise_max_lots=4,
    momentum_traders=3,
    momentum_threshold=0.0006,
    momentum_lots=3,
    momentum_max_lots=15,
)

SCENARIOS = {s.name: s for s in (CALM, VOLATILE)}
