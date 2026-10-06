"""Realism statistics (S14): does the simulated market look like a market?

Pure functions over trades and spread samples, so they are unit-tested; ``realism_report`` (in
``tests/e2e``) collects the data from a running stack. Three stylised facts of real markets:

1. Returns are fat-tailed: excess kurtosis above 0 (a normal distribution has 0).
2. Returns are nearly uncorrelated (prices are hard to predict), but volatility clusters: the
   autocorrelation of absolute returns is positive for several lags.
3. The bid-ask spread is small and stable while a market maker is active.
"""

from dataclasses import dataclass

import numpy as np


@dataclass(frozen=True)
class ReturnStats:
    count: int
    mean_bps: float
    std_bps: float
    skew: float
    excess_kurtosis: float
    min_bps: float
    max_bps: float
    return_autocorr: list[float]
    abs_return_autocorr: list[float]


@dataclass(frozen=True)
class SpreadStats:
    samples: int
    mean_ticks: float
    median_ticks: float
    p95_ticks: float
    mean_bps: float
    two_sided_share: float


def bucket_prices(trades: list[tuple[int, int]], bucket_micros: int) -> np.ndarray:
    """Last trade price in each time bucket, from ``(sim_time_micros, price)`` pairs in any order.
    Buckets with no trade carry the previous price forward."""
    if not trades:
        return np.array([], dtype=float)
    data = np.array(sorted(trades), dtype=np.int64)
    buckets = data[:, 0] // bucket_micros
    first, last = buckets[0], buckets[-1]
    out = np.empty(last - first + 1, dtype=float)
    out[:] = np.nan
    for b, price in zip(buckets, data[:, 1], strict=True):
        out[b - first] = price  # later trades in a bucket overwrite earlier ones
    for i in range(1, len(out)):
        if np.isnan(out[i]):
            out[i] = out[i - 1]
    return out


def log_returns(prices: np.ndarray) -> np.ndarray:
    prices = prices[~np.isnan(prices)]
    if len(prices) < 2:
        return np.array([], dtype=float)
    return np.diff(np.log(prices))


def autocorr(x: np.ndarray, lags: int) -> list[float]:
    """Sample autocorrelation at lags 1..``lags`` (0.0 where undefined)."""
    x = np.asarray(x, dtype=float)
    if len(x) <= lags + 1 or np.var(x) == 0:
        return [0.0] * lags
    d = x - x.mean()
    denom = float(np.dot(d, d))
    return [float(np.dot(d[:-k], d[k:]) / denom) for k in range(1, lags + 1)]


def return_stats(returns: np.ndarray, lags: int = 5) -> ReturnStats:
    r = np.asarray(returns, dtype=float)
    if len(r) < 3 or np.std(r) == 0:
        return ReturnStats(len(r), 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, [0.0] * lags, [0.0] * lags)
    m, s = r.mean(), r.std()
    z = (r - m) / s
    return ReturnStats(
        count=len(r),
        mean_bps=float(m * 1e4),
        std_bps=float(s * 1e4),
        skew=float(np.mean(z**3)),
        excess_kurtosis=float(np.mean(z**4) - 3.0),
        min_bps=float(r.min() * 1e4),
        max_bps=float(r.max() * 1e4),
        return_autocorr=autocorr(r, lags),
        abs_return_autocorr=autocorr(np.abs(r), lags),
    )


def spread_stats(samples: list[tuple[int | None, int | None]], tick: int) -> SpreadStats:
    """Spread statistics from ``(best_bid, best_ask)`` samples in paise; one-sided samples count
    against ``two_sided_share`` only."""
    if not samples:
        return SpreadStats(0, 0.0, 0.0, 0.0, 0.0, 0.0)
    both = [(b, a) for b, a in samples if b is not None and a is not None and a > b]
    if not both:
        return SpreadStats(len(samples), 0.0, 0.0, 0.0, 0.0, 0.0)
    bids = np.array([b for b, _ in both], dtype=float)
    asks = np.array([a for _, a in both], dtype=float)
    ticks = (asks - bids) / tick
    bps = (asks - bids) / ((asks + bids) / 2) * 1e4
    return SpreadStats(
        samples=len(samples),
        mean_ticks=float(ticks.mean()),
        median_ticks=float(np.median(ticks)),
        p95_ticks=float(np.percentile(ticks, 95)),
        mean_bps=float(bps.mean()),
        two_sided_share=len(both) / len(samples),
    )
