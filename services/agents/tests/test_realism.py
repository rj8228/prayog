import numpy as np
from prayog_agents.realism import (
    autocorr,
    bucket_prices,
    log_returns,
    return_stats,
    spread_stats,
)


def test_bucket_prices_keeps_the_last_trade_and_fills_gaps():
    trades = [
        (65, 101),
        (10, 100),
        (59, 102),
        (190, 105),
    ]  # buckets of 60: [0]=100,102 [1]=101 [3]=105
    prices = bucket_prices(trades, 60)
    assert prices.tolist() == [102, 101, 101, 105]


def test_normal_returns_have_no_excess_kurtosis_and_no_autocorrelation():
    r = np.random.default_rng(1).normal(0, 0.001, 50_000)
    s = return_stats(r)
    assert abs(s.excess_kurtosis) < 0.1
    assert abs(s.skew) < 0.05
    assert all(abs(c) < 0.02 for c in s.return_autocorr)
    assert all(abs(c) < 0.02 for c in s.abs_return_autocorr)
    assert abs(s.std_bps - 10) < 0.2


def test_volatility_clustering_shows_in_absolute_returns():
    rng = np.random.default_rng(2)
    # GARCH(1,1)-like: today's variance depends on yesterday's shock.
    n, var, r = 20_000, 1e-6, []
    for _ in range(n):
        x = rng.normal(0, np.sqrt(var))
        r.append(x)
        var = 1e-7 + 0.15 * x * x + 0.8 * var
    s = return_stats(np.array(r))
    assert s.excess_kurtosis > 0.5  # fat tails
    assert abs(s.return_autocorr[0]) < 0.05  # returns themselves unpredictable
    assert s.abs_return_autocorr[0] > 0.1  # but volatility clusters
    assert s.abs_return_autocorr[4] > 0.05


def test_spread_stats_in_ticks_and_basis_points():
    samples = [(149_990, 150_000), (149_990, 150_010), (None, 150_000), (149_995, 150_000)]
    s = spread_stats(samples, tick=5)
    assert s.samples == 4
    assert s.two_sided_share == 0.75
    assert s.median_ticks == 2
    assert abs(s.mean_ticks - (2 + 4 + 1) / 3) < 1e-9
    assert 0.6 < s.mean_bps < 0.8


def test_degenerate_inputs_do_not_crash():
    assert return_stats(np.array([])).count == 0
    assert autocorr(np.ones(10), 3) == [0.0, 0.0, 0.0]
    assert log_returns(np.array([100.0])).size == 0
    assert spread_stats([], tick=5).samples == 0
