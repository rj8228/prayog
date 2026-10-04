import pytest
from prayog_agents.quoting import Ema, maker_quotes, momentum_signal, round_to_tick

PARAMS = dict(
    tick=5,
    lot=10,
    half_spread_ticks=2,
    levels=3,
    level_step_ticks=2,
    quote_lots=4,
    skew_ticks_per_lot=0.5,
    max_inventory_lots=60,
    band_low=135_000,
    band_high=165_000,
)


def test_rounding_never_makes_a_quote_more_aggressive():
    assert round_to_tick(150_003, 5, "BUY") == 150_000
    assert round_to_tick(150_003, 5, "SELL") == 150_005
    assert round_to_tick(150_005, 5, "SELL") == 150_005


def test_flat_maker_quotes_both_sides_symmetrically():
    quotes = maker_quotes(150_000, 0, **PARAMS)
    bids = sorted((q for q in quotes if q.side == "BUY"), key=lambda q: q.level)
    asks = sorted((q for q in quotes if q.side == "SELL"), key=lambda q: q.level)
    assert [q.price for q in bids] == [149_990, 149_980, 149_970]
    assert [q.price for q in asks] == [150_010, 150_020, 150_030]
    assert all(q.quantity == 40 for q in quotes)


@pytest.mark.parametrize("fair", [150_001.7, 149_997.2, 135_400.0, 164_600.9])
def test_quotes_are_on_tick_inside_the_band_and_never_crossed(fair):
    quotes = maker_quotes(fair, 0, **PARAMS)
    assert all(q.price % 5 == 0 and 135_000 <= q.price <= 165_000 for q in quotes)
    best_bid = max((q.price for q in quotes if q.side == "BUY"), default=0)
    best_ask = min((q.price for q in quotes if q.side == "SELL"), default=10**9)
    assert best_bid < best_ask


def test_long_inventory_skews_quotes_down():
    flat = maker_quotes(150_000, 0, **PARAMS)
    long = maker_quotes(150_000, 200, **PARAMS)  # 20 lots long
    assert min(q.price for q in long if q.side == "SELL") < min(
        q.price for q in flat if q.side == "SELL"
    )
    assert max(q.price for q in long if q.side == "BUY") < max(
        q.price for q in flat if q.side == "BUY"
    )


def test_at_max_inventory_the_maker_stops_adding_to_it():
    quotes = maker_quotes(150_000, 600, **PARAMS)  # 60 lots long = the limit
    assert {q.side for q in quotes} == {"SELL"}


def test_ema_converges_and_momentum_reads_the_gap():
    fast, slow = Ema(2), Ema(20)
    for _ in range(5):
        fast.update(100.0)
        slow.update(100.0)
    for _ in range(10):
        fast.update(101.0)
        slow.update(101.0)
    assert fast.value > slow.value
    assert momentum_signal(fast.value, slow.value, 0.001) == 1
    assert momentum_signal(slow.value, fast.value, 0.001) == -1
    assert momentum_signal(100.0, 100.0, 0.001) == 0
    assert momentum_signal(None, 100.0, 0.001) == 0
