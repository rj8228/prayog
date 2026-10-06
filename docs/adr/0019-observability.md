# 19. Observability: Prometheus and Grafana, one dashboard for the order path and after the trade

Date: 2026-10-07

## Status

Accepted

## Context

S21 asks for Micrometer metrics, Prometheus and Grafana dashboards showing latency, throughput, queue depth and
consumer lag of a live session. The exchange already exported order counts, an order-latency histogram, ring room and
the input seq. The Kafka publisher, snapshots and the post-trade consumer had no metrics.

## Decisions

1. **New metrics:**
   - Exchange: `prayog_kafka_lag`, `prayog_kafka_connected`, `prayog_kafka_published_seq`, `prayog_snapshot_input_seq`.
   - Post-trade: `prayog_posttrade_batch_seconds` (p50, p99 of one ledger transaction), `prayog_posttrade_last_event`.
   - Spring Kafka's client metrics give the consumer's own lag (`kafka_consumer_fetch_manager_records_lag_max`).
2. **Compose profile `obs`:** Prometheus v3 (5 s scrape, 2-day retention) and Grafana 12. Both are pinned, with health
   checks. Grafana is at `grafana.prayog.localhost`. It is optional: `make up PROFILES="infra app obs"`. The default
   stack stays within the 4 GB Docker Desktop budget.
3. **Everything as code:** the Prometheus config, the Grafana datasource, the dashboard provider and the dashboard
   JSON are files in `deploy/compose/`, provisioned at start. Nothing is clicked together by hand, and the dashboard
   cannot drift from git.
4. **One dashboard, "Prayog exchange":**
   - A top row of numbers: commands per second, new-order p99, ring in use, Kafka connected and lag, and how far the
     ledger is behind.
   - Graphs below: orders by outcome, latency p50/p99/p99.9, ring in use, Kafka lag, post-trade applied and duplicate
     events, consumer lag and batch time, GC pauses and heap.
5. **Anonymous viewing, local only.** Viewers need no login; editing needs the admin password from `.env`
   (`GRAFANA_ADMIN_PASSWORD`).

## Testing

- `promtool check config` validates the Prometheus file; Grafana starts healthy with the dashboard provisioned
  (14 panels).
- Live check: on a running session the panels show trading, and stopping Kafka makes the lag graph rise and fall
  (runbook 16).

## Trade-offs

- Prometheus scrapes every 5 s. Sub-second spikes appear only in the histogram's percentiles, not as points.
- No alerting rules yet. The thresholds on the stat panels (lag above 1,000 is orange, above 50,000 red) are the
  first draft of them.
