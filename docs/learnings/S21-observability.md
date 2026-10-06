# S21 Observability

**Built:** Prometheus scrapes the exchange and post-trade every 5 s, and a provisioned Grafana dashboard shows the
order path and everything after the trade ([ADR 0019](../adr/0019-observability.md), runbook 16).

## Concepts

- [ ] **The four golden signals:** latency, traffic, errors, saturation. Here: order latency percentiles, commands per
  second, orders by outcome (rejected, rate limited), and ring in use / Kafka lag.
- [ ] **Pull model.** Prometheus scrapes `/actuator/prometheus`; services keep counters and gauges in memory and know
  nothing about who reads them.
- [ ] **Counter, gauge, histogram.** Counters only go up (use `rate()`); gauges go up and down; histograms keep
  buckets so percentiles can be computed across time and instances (`histogram_quantile`).
- [ ] **Lag is the queue-depth metric of a stream.** Producer lag (journal → Kafka) and consumer lag (Kafka → ledger)
  together show where a backlog sits.
- [ ] **Dashboards as code.** Provisioned from files in git, so every environment shows the same thing.

## Explain-back: questions and model answers

### 1. Why is `prayog_orders_total` a counter and plotted with `rate()`, while `prayog_kafka_lag` is a gauge?

**Answer:** Orders only accumulate: the useful question is "how many per second lately", which `rate()` computes from
the counter's increase, and it survives restarts (Prometheus handles the reset to zero). Lag goes up and down: the
current value is the meaning, so it is a gauge read as is.

### 2. Why compute latency percentiles from histogram buckets rather than export a "p99" number?

**Answer:** A p99 computed inside one process cannot be averaged with another's or over a longer window: the average
of p99s is not a p99. Buckets can be summed across instances and time, and `histogram_quantile` then gives a real
percentile for any window. The exchange exports both: buckets for Grafana, a few precomputed percentiles for the
admin console.

### 3. Kafka is stopped for a minute. Which panels move, and in which order?

**Answer:** *Kafka connected* turns red and *Kafka lag* climbs at the rate events are journaled, while *Commands / s*
and *Order latency* stay flat: trading does not depend on Kafka (ADR 0013). *Ledger behind exchange* stays roughly
flat at first, because the ledger can only see what was published. When Kafka returns, the publisher's lag falls to
zero within seconds, then the consumer's lag spikes and drains as post-trade catches up, and *Ledger behind
exchange* returns to ~0.

### 4. Why is the dashboard a JSON file in git instead of something built in the Grafana UI?

**Answer:** So it is reviewed, versioned and identical on every machine and in a demo. A hand-built dashboard lives
only in one Grafana's database and is lost with the volume. Provisioning also makes Grafana stateless here: it needs
no volume at all.

### 5. What would you alert on first, and why those?

**Answer:** On what threatens correctness or clients, not on every wiggle:
- Kafka lag above a threshold for several minutes, since downstream numbers go stale.
- Ledger behind the exchange, for the same reason.
- Ring in use above half, because back-pressure is close and orders will start being refused.
- Order latency p99 above a budget.
- Any rise in `rejected` beyond normal, which could be a misbehaving bot or a rule change.

The stat-panel thresholds are a first draft of these.

## In an interview

"Prometheus scrapes Micrometer metrics from both services; one provisioned Grafana dashboard covers the four golden
signals for the order path, and lag on both sides of Kafka. When Kafka goes down you watch trading stay flat while
producer lag climbs, then drain on recovery."
