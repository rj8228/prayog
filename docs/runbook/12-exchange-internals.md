# 12. Exchange internals

| Question | Command | Expect |
|---|---|---|
| Is it ready? | `curl -s http://api.prayog.localhost/actuator/health/readiness` | `{"status":"UP"}` |
| Session and sim time | `curl -s http://api.prayog.localhost/api/v1/session` | `state`, `simTime` (epoch µs), multiplier, hours |
| Order outcomes and latency | `curl -s http://api.prayog.localhost/actuator/prometheus \| grep -E '^prayog_'` | `prayog_orders_total{kind,outcome}`, `prayog_order_latency_seconds_*`, `prayog_ring_remaining` (65,536 when idle), `prayog_input_seq` |
| Rejected orders | `docker logs prayog-exchange-1 2>&1 \| grep '"status":"rejected"'` | One JSON line per rejected order with input seq, account id, client, reason |
| Recovery after restart | `dc restart exchange` (the `dc` alias from the runbook README), then `docker logs prayog-exchange-1 2>&1 \| grep recovered` | `recovered session ...: N commands, M events, 0 events repaired; K commands replayed after the snapshot at input seq S` (K small: the stop took a snapshot) |
| Snapshots | `dc exec exchange ls -l /data/journal \| grep snap`; take one now: `curl -s -X POST -H "Authorization: Bearer $(make -s ops-token)" http://api.prayog.localhost/api/v1/ops/snapshot` | The newest 3 `snapshot-<seq>.snap` files; `lastSnapshotInputSeq` in `/ops/status` (ADR 0016) |
| Deterministic replay of the live journal | `make e2e` (the last part) | `MATCH` with the same SHA-256 recorded and replayed, then `snapshots N verified` |
| Whole-market correctness | `make e2e` | `20/20 checks passed`, `MATCH`, `E2E passed.` |

The journal lives in the `prayog_exchange-journal` Docker volume. `make reset` deletes it (a brand-new market).
Clock ticks add 10 records a second, so a day of running adds a few hundred thousand records; that is expected.

Ops actions (`/api/v1/ops/...`: halt, kill switch, clock) need a token with role `ops`. Today only the browser flow
issues one (sign in as `ops1`); the ops page in S19 will use it. With the default `autoNextDay`, the clock rolls to
the next open by itself 30 s after the close.
