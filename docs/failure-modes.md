# Failure modes

What can break, how we notice, what it affects, how it recovers, and which test proves it. "Drill" points at
[runbook 6](runbook/06-failure-drills.md) and the other runbooks.

## The order path

| Failure | Detected by | Effect | Recovery | Proven by |
|---|---|---|---|---|
| Exchange process crashes or is killed | Health check; Compose restarts it | Orders in flight get no answer; nothing acknowledged is lost (answers only after fsync) | Recovery loads the newest snapshot, replays the input after it, verifies every replayed event against the event log, repairs a missing event-log tail | `JournalRecoveryTest`, `SnapshotTest`, `ExchangeApiTest` restarts; `make e2e` restart from snapshot |
| Crash mid-write: half a journal record | CRC32C / length check on open | That record was never fsynced, so no client was told about it | Cut off as a torn tail (ADR 0007) | `FileJournalTest`, `JournalTailerTest` |
| A journal segment damaged in the middle | CRC check during recovery | Exchange refuses to start | Restore the disk; the journal is the source of truth | `FileJournalTest.damageInAnEarlierSegmentIsCorruptionNotATornWrite` |
| Replay does not reproduce the event log (rule changed without `SetRules`, non-determinism) | Recovery's byte-for-byte comparison; `make e2e` replay check | Exchange refuses to start rather than trade on a different book | Fix the code, or rebuild the derived event log from the input journal (runbook 7) | `JournalRecoveryTest` doctored log; ADR 0014 incident |
| Snapshot file damaged | CRC check on load | None: an older snapshot or a full replay is used | Automatic fallback | `SnapshotTest` damaged newest falls back |
| Snapshot differs from what replay reaches | `ReplayCheck` compares every snapshot | Would make recovery wrong; caught before use in CI/e2e | Delete the snapshot; fix the bug | `Replay.checkSnapshots`, `make e2e` |
| Ring full (a slow stage) | `prayog_ring_remaining`; self-test "ring has room" | New orders refused with `503 busy`; nothing queues without bound | Clients retry; the slow stage catches up | `ExchangePipelineTest.trySubmitRefusesWhenTheRingIsFull` |
| Client floods orders | Token bucket per account and tier | That account gets `429`; others unaffected | Automatic refill | `ExchangeApiTest` rate-limit tests (ADR 0020) |
| Client retries after a timeout | Duplicate client order id check (rules v2) | Retry rejected `DUPLICATE_CLIENT_ORDER_ID`; no second order | None needed | `ExchangeRulesTest`, Cucumber scenario, API test |
| Slow market-data subscriber | Per-subscriber buffer overflow | That subscriber is disconnected; nobody else slows down | Client reconnects and gets a fresh snapshot | SDK reconnect; `market_check` feed integrity |
| A gap in a market-data feed | Per-symbol sequence numbers | Client's book would be wrong | SDK and web detect the gap and resync from a snapshot | `test_book.py`, `market_check` |
| Admin halts or closes the market by mistake | Session state on every page | Halt: cancels only; close: open orders expire | Ops page: Open | `ExchangeRulesTest` sessions; Cucumber |

## After the trade

| Failure | Detected by | Effect | Recovery | Proven by |
|---|---|---|---|---|
| Kafka down or hung | Publisher `connected=false`, `prayog_kafka_lag` rising, self-test check 7 | Trading continues; events wait in the journal | Publisher resends from its contiguous-acked checkpoint; duplicates allowed, gaps impossible | `KafkaPublisherIT` (paused broker), `kafka_check.py` (stopped broker, every id 1..N checked) |
| Exchange crashes with events sent but not acked | Checkpoint below the sent events | Some events published twice | Consumers skip by event id | `KafkaEventPublisherTest` |
| Post-trade down | Health check; "ledger behind exchange" in Grafana and the ops page | P&L and leaderboard stale; trading unaffected | Resumes from committed offsets; redelivered batches are no-ops | `posttrade_check.py` (stopped mid-session, zero-sum after) |
| PostgreSQL down | Post-trade batch fails | Ledger stalls | Batch retried every second until it succeeds (never skipped) | Error handler config (ADR 0015) |
| A batch fails half-way | Database error | Nothing of that batch is applied | Whole batch retried in a new transaction | `PostTradeIT.aBatchIsOneTransaction` |
| Redis down or emptied | Leaderboard update fails, or the set is empty while trades exist | Leaderboard stale or empty | Rebuilt from PostgreSQL and swapped in atomically | `PostTradeIT` (key deleted, rebuilt) |
| Ledger loses or doubles a fill | Zero-sum totals (P&L before charges and net quantity per symbol must be 0) | Wrong P&L | Rebuild the ledger from the topic (runbook 5) | `LedgerIT` reconciliation; `posttrade_check.py` |

## Around the system

| Failure | Detected by | Effect | Recovery | Proven by |
|---|---|---|---|---|
| Keycloak down | Health check | New sign-ins and token renewals fail; valid tokens keep working until they expire (signatures are checked offline) | Restart Keycloak | Smoke checks |
| Simulated traders crash | `docker compose ps`; trades stop (self-test check 5) | Market goes quiet; book thins out | Compose restarts them; a paused maker cancels its quotes | `test_control.py`, `market_check` liquidity |
| Laptop sleeps | Clock jumps; tokens look expired | Brief 401s after wake | Wait for Docker's clock to resync; sign in again (runbook 7) | Known problem, runbook 7 |
| Docker out of memory | Containers restart or `make up` times out | Services flap | Give Docker 5 GB; `docker stats` | Runbook 7 |
