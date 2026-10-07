# S14 Realism checks

**Built:** `prayog_agents.realism` (return, autocorrelation and spread statistics, unit tested) and
`tests/e2e/realism_report.py`, which watches the live public feed for each scenario and writes
[docs/realism.md](../realism.md). Run 2026-10-08, 600 s per scenario at 1x.

## Result, read honestly

| Stylised fact | Calm | Volatile | Verdict |
|---|---|---|---|
| Little autocorrelation in returns | within ±0.10 | within ±0.06 | matches |
| Fat tails (excess kurtosis > 0) | +0.10 | +0.96 | weak in calm, mild in volatile |
| Volatility clustering (absolute returns autocorrelated) | within ±0.10 | within ±0.08 | **not reproduced** |
| Tight, always two-sided spread | 1.33 bps, 100% | 2.40 bps, 100% | matches |

With about 240 ten-second returns per scenario, an autocorrelation's standard error is about 1/sqrt(240) = 0.065, so
anything inside ±0.13 is indistinguishable from zero. The fair value is a mean-reverting random walk with jumps at a
**constant** volatility, and jumps are rare and independent: nothing makes a volatile minute more likely after another
one. Real markets cluster because news and order flow arrive in bursts. Backlog: let volatility itself follow a
process (a GARCH-like or regime-switching sigma, or self-exciting jumps), then rerun the report for longer.

## Concepts

- [ ] **Stylised facts.** Statistical regularities seen in almost every market: fat tails, near-zero return
  autocorrelation, volatility clustering, tight spreads where liquidity is provided. A simulator is judged against
  them, not against any one real price series.
- [ ] **Kurtosis.** The fourth moment; excess kurtosis 0 for a normal distribution, positive when extreme moves are
  more common than a bell curve predicts.
- [ ] **Autocorrelation of returns vs of absolute returns.** The first near zero means prices are hard to predict;
  the second positive means the size of moves is predictable (calm follows calm, storms follow storms).
- [ ] **Sampling error.** A statistic from a short window has an error bar; 1/sqrt(n) for an autocorrelation.
- [ ] **Measuring from the outside.** The report uses only the public feed, like any participant, so it measures what
  traders actually see, including the market maker's quoting, not just the hidden fair value.

## Explain-back: questions and model answers

### 1. Why are returns computed over 10 s of sim time rather than trade by trade?

**Answer:** Trade-to-trade returns are dominated by bid-ask bounce: a buy at the ask followed by a sell at the bid
looks like a return of one spread and then its reverse, which shows up as strong negative autocorrelation that has
nothing to do with price discovery. Bucketing into fixed sim-time intervals and taking the last trade price in each
removes most of the bounce and makes calm and volatile comparable.

### 2. The volatile scenario has 3x the return std but no more volatility clustering than calm. Why?

**Answer:** Volatile only scales the fair value's constant volatility and jump size up. Clustering needs volatility to
vary over time with memory. A scaled constant has no memory, so absolute returns stay uncorrelated whatever the scale.

### 3. Is an excess kurtosis of +0.96 evidence of fat tails?

**Answer:** Weakly. Real 10-second equity returns often show excess kurtosis well above 3. With about 240 returns the
estimate is noisy (kurtosis is very sensitive to the few largest moves), and the volatile scenario's jumps produce
it. A longer run would narrow it; the calm scenario's +0.10 is effectively normal.

### 4. Why does the report read the public feed instead of the simulator's fair-value series?

**Answer:** The fair value is hidden: traders see trades and quotes, shaped by the market maker's spread, inventory
skew and the order book. Realism is about what participants experience, so it is measured where they measure it. It
also tests the whole chain (agents, gateway, engine, market data) rather than one Python function.

### 5. What would you change first to reproduce volatility clustering, and how would you know it worked?

**Answer:** Make sigma a process: for example, each sim second sigma moves toward a long-run level but a jump or news
event multiplies it and it decays back (a GARCH(1,1)-like recursion, or self-exciting jumps where a jump raises the
chance of the next). Then rerun the report for at least an hour per scenario and require absolute-return
autocorrelation at lags 1-5 clearly above the ±2/sqrt(n) noise band while return autocorrelation stays inside it.
