# 13. Kafka event publisher: tail the durable event log, file checkpoint, ops-tool client

Date: 2026-10-07

## Status

Accepted

## Context

S15 publishes every exchange event to Kafka (`prayog.exchange.events.v1`, 3 partitions, JSON, keyed by symbol) for
post-trade and anything else downstream. BUILD_PLAN 16.1 #18-19 fixed the delivery model: at least once, event id =
the exchange's event seq, consumers idempotent, and Kafka downtime must never stop trading or lose events.

Three choices were open: where the publisher takes events from, where it remembers how far it got, and how scripts
(the e2e checks) get an ops token to watch it.

## Decisions

1. **Tail the event log on disk, not a pipeline stage.** A new `JournalTailer` (exchange-core) follows the event log
   like `tail -f`, remembering its segment and byte position. The publisher runs on its own thread
   (`prayog-kafka-publisher`) and reads only up to `JournalHandler.durableEventSeq()`, the last event the journal
   stage has fsynced. So:
   - Kafka can never slow the ring: a Disruptor stage that blocked on Kafka would fill the ring and halt trading.
   - Live publishing and catching up after downtime are one loop: "send everything after the checkpoint".
   - Nothing is published that a crash could still erase (an event on Kafka that the recovered exchange never
     produced would be a phantom trade downstream).
   - Cost: a few milliseconds of extra delay (the tailer polls when idle). Post-trade does not need microseconds.
2. **Checkpoint in a file next to the journal** (`kafka-publisher.checkpoint`). It holds the *contiguous acked
   line*: the highest seq such that it and every earlier event were acknowledged. Partitions ack independently, so
   "the last ack" could skip an unacked event on another partition. Saved once a second and on shutdown by atomic
   rename, not fsynced: losing a save only means resending, which at-least-once allows.
   - Rejected: reading the last record of each partition from Kafka at start-up. It needs Kafka to be up to know
     where to start, and with three partitions the highest id seen does not prove the lower ones arrived.
3. **Producer settings:** `acks=all`, idempotence on (no duplicates from the producer's own retries, order kept per
   partition), lz4, short `max.block.ms`/`delivery.timeout.ms` so an outage is noticed in seconds. On any failed
   send the producer is closed, everything in flight forgotten, and sending resumes from the checkpoint after 1 s.
4. **Format shared by writer and readers:** `EventJson` in `contracts` (the schema's shape, `type` first, unknown
   fields ignored on read). Session-wide events have the empty key, as the schema says. Each record also carries an
   `eventId` header.
5. **Self-test check 7** (Kafka publisher keeping up) and `kafka` in `GET /ops/status`: enabled, connected,
   published seq, lag, errors.
6. **`prayog-ops-tool` client** (client credentials, roles `ops` and `admin`, secret `PRAYOG_OPS_TOOL_SECRET`). Keycloak
   has no password grant (ADR 0008), so scripts had no way to act as ops. Added to the realm file and, for existing
   installs, by `users.sh`. Local development only, like the other confidential clients.

## Testing

- `JournalTailerTest`: reads across segments, starts mid-journal, never past the durable seq, follows a journal while
  it is written, waits at a half-written record, fails on a corrupt record below the durable line.
- `PublishCheckpointTest`: out-of-order acks, rewinds, damaged files.
- `KafkaEventPublisherTest` (Kafka's `MockProducer`): order, keys and headers; nothing before it is durable; a failed
  send resends from the checkpoint and only from there; restart resumes.
- `KafkaPublisherIT` (Testcontainers Kafka 4.3): pause the broker mid-session until sends fail, trade on, unpause,
  restart the exchange, trade more; the topic then holds exactly the journal's events (duplicates allowed, no gaps,
  each copy equal to the journal's event).
- `make e2e` → `tests/e2e/kafka_check.py`: stops the real Kafka container for 8 s while the simulated traders trade,
  checks trading carried on and the lag grew, then that the publisher caught up and every event 1..N is on the
  topic (849,187 events checked on the long-running local stack).
- Planted bugs caught: tailer ignoring the durable seq; checkpoint saving the last ack instead of the line; resuming
  from "now" instead of the checkpoint after a failure.

## Trade-offs

- Duplicates on Kafka after an outage or crash are expected; every consumer must deduplicate by event id (S16 stores
  the last applied id per partition in the same transaction as its update).
- The first start after enabling Kafka publishes the whole journal (~800k events took ~20 s locally).
- No order across partitions; a consumer that needs a global order sorts by event id.
