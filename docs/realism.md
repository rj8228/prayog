# Realism report (S14)

Generated 2026-10-08 by `uv run python tests/e2e/realism_report.py --seconds 600 --bucket 10` on the local stack (clock at 1x).

Stylised facts of real markets to compare against: returns with fat tails (excess kurtosis above 0), little autocorrelation in returns, positive autocorrelation in absolute returns (volatility clustering), and a tight, always two-sided spread while a market maker is active.

## Scenario `calm`

| Measure | Value |
|---|---|
| Trades observed | 1,094 |
| 10-second returns (pooled, 4 symbols) | 239 |
| Return std | 17.46 bps |
| Smallest / largest return | -49.8 / 51.0 bps |
| Skew | +0.12 |
| Excess kurtosis (0 = normal) | +0.10 |
| Autocorrelation of returns, lags 1-5 | -0.01, +0.01, -0.01, +0.10, -0.03 |
| Autocorrelation of absolute returns, lags 1-5 | +0.07, -0.10, -0.05, +0.07, -0.06 |
| Spread: mean / median / p95 | 5.67 / 5.0 / 7.0 ticks (1.33 bps) |
| Two-sided book | 100.0% of 3,725 samples |

## Scenario `volatile`

| Measure | Value |
|---|---|
| Trades observed | 2,017 |
| 10-second returns (pooled, 4 symbols) | 236 |
| Return std | 54.05 bps |
| Smallest / largest return | -158.2 / 214.9 bps |
| Skew | +0.26 |
| Excess kurtosis (0 = normal) | +0.96 |
| Autocorrelation of returns, lags 1-5 | -0.03, +0.05, -0.04, -0.06, -0.04 |
| Autocorrelation of absolute returns, lags 1-5 | +0.01, -0.08, -0.08, +0.03, -0.04 |
| Spread: mean / median / p95 | 10.01 / 9.0 / 12.0 ticks (2.40 bps) |
| Two-sided book | 100.0% of 3,673 samples |

