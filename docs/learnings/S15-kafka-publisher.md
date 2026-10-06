# S15 Kafka publisher

**Built:** every exchange event now flows to Kafka topic `prayog.exchange.events.v1`, off the order path, with no
loss across Kafka outages or exchange restarts ([ADR 0013](../adr/0013-kafka-event-publisher.md)).

- `JournalTailer` follows the event log on disk like `tail -f`.
- `KafkaEventPublisher` sends what the tailer reads and keeps a checkpoint of what Kafka has acknowledged.
- `EventJson` (in `contracts`) is the one JSON format shared by the exchange and its consumers.
- `make e2e` now stops Kafka mid-session and proves every event still arrives.

Runbook: [5. Data and messaging](../runbook/05-data-and-messaging.md#the-exchanges-event-stream-s15).

## Concepts

- [ ] **Topic, partition, offset.** A topic is split into partitions; each partition is an append-only log, and a
  record's offset is its position in that partition. Order is guaranteed only inside one partition.
- [ ] **Keys pick partitions.** `hash(key) % partitions`. Keying by symbol keeps each symbol's events in order.
  Session-wide events use the empty key, which always maps to the same partition.
- [ ] **Offsets are not event ids.** A resent event gets a new offset. Consumers must deduplicate on our own id (the
  exchange's event seq), never on the offset.
- [ ] **Delivery guarantees.** At most once (may lose), at least once (may duplicate), exactly once (needs
  transactions end to end). We chose at least once plus idempotent consumers: simple, and nothing is ever lost.
- [ ] **Idempotent producer.** `enable.idempotence=true` lets the broker drop duplicates caused by the producer's
  own internal retries and keeps per-partition order. It does not help after the producer restarts: that is the
  consumer's job.
- [ ] **`acks=all`.** A send counts as done only once the broker has stored it. With one broker this means "on that
  broker's log"; with replicas it means "on every in-sync replica".
- [ ] **Contiguous acked line.** Acks arrive out of order across partitions. The checkpoint is the highest seq with
  everything below it acked, so a restart never skips an event that was still in flight.
- [ ] **Durable before published.** The publisher reads only events the journal has fsynced. Otherwise a crash could
  erase an event that downstream systems already acted on (a phantom trade).
- [ ] **Decoupling by log tailing.** Reading the journal file instead of being a pipeline stage means a slow
  consumer can never back-pressure the matching engine. This is the *transactional outbox* idea: the journal is the
  outbox.
- [ ] **Chaos testing.** Pause or stop the broker on purpose, in an integration test (Testcontainers) and on the real
  stack (`make e2e`), and check the invariant "every event 1..N is on the topic".

## Explain-back: questions and model answers

### 1. Why isn't the Kafka publisher just another Disruptor stage after the journal, like market data?

**What it's asking:** what goes wrong when an unreliable downstream system sits inside a pipeline with
back-pressure.

**Background:** A Disruptor ring has a fixed number of slots. The slowest stage decides how fast slots are freed;
when the ring is full, producers (the gateway) cannot submit and the exchange answers "busy" (ADR 0006).

**Prayog example:** Kafka is down for 8 seconds while the simulated traders send about 50 commands a second, each
producing a few events. As a stage, the publisher would block on `send()` (up to `max.block.ms`) or have to buffer.
Blocking holds slots; after enough of them the ring fills and every order is refused. Buffering in memory without a
limit risks running out of memory; buffering with a limit just moves the problem.

**Answer:**
- A pipeline stage couples trading to Kafka's health. The exchange's promise is the opposite: "Kafka downtime never
  stops trading" (BUILD_PLAN 16.1 #19).
- The journal already holds every event durably, in order, with a seq. Treating it as an *outbox* and tailing it on a
  separate thread gives:
  - complete isolation: the matching thread never waits for the publisher;
  - free buffering of any size: the disk is the buffer;
  - one code path for normal running and for catch-up after an outage or a restart.
- The cost is a few milliseconds of delay (the tailer sleeps 5 ms when idle) and one extra read of each event from
  the page cache. Post-trade can easily live with that.
- `make e2e` shows it: during the outage the input seq kept rising (trading carried on) while the publisher's lag
  grew, then fell back to zero.

### 2. Why does the publisher only read up to `durableEventSeq`, when the bytes are already in the file?

**What it's asking:** the difference between "written" and "durable", and what happens downstream if you publish too
early.

**Background:** `FileJournal.append` puts records in a memory buffer. It writes them to the file when the buffer
fills or on `flush()`, and only `flush()` calls `force()` (fsync). Between a write and the fsync the bytes are in the
operating system's page cache: visible to readers, but lost if the machine loses power.

**Prayog example:** Suppose events 900-905 (a trade among them) have been written but not yet fsynced, and the
tailer publishes them. The machine crashes. On restart, recovery rebuilds the exchange from the input journal, and
the commands behind 900-905 were never fsynced either, so they do not exist. New, different events now get seqs
900-905. Post-trade has already booked the old trade 900 and will skip the new event 900 as a duplicate. Positions
are now wrong forever.

**Answer:**
- The journal stage fsyncs at the end of each Disruptor batch and only then publishes `durableEventSeq` (a volatile
  write). Everything at or below it survives any crash.
- The tailer never hands over a record above that line, so nothing downstream can see an event that recovery might
  erase or renumber.
- The same rule protects clients: the outbound stage answers only after the journal stage (ADR 0006).
- Below the durable line a damaged record cannot be "not written yet", so the tailer treats it as corruption and
  throws rather than skipping data.

### 3. Kafka acknowledged events 101, 102 and 104 but not 103. What should the checkpoint say, and what happens after a crash?

**What it's asking:** why "last acknowledged" is the wrong checkpoint when sends complete out of order.

**Background:** The topic has 3 partitions. Each partition has its own batches and requests, so acks from different
partitions come back in any order. Within one partition, order is kept.

**Prayog example:** 101 (INFY, partition 0), 102 (TCS, partition 2), 103 (RELIANCE, partition 1) and 104 (INFY,
partition 0) are sent. Partitions 0 and 2 answer quickly; partition 1's request is still in flight when the exchange
is stopped.

**Answer:**
- The checkpoint must say **102**, the contiguous line: every event up to 102 is acked, and 103 is not.
  `PublishCheckpoint` keeps a sorted set of in-flight seqs and sets the line to "first in flight − 1".
- After a restart the publisher sends from 103, so 103 and 104 go out again. 104 is now on the topic twice; 103 is
  there at least once. Nothing is lost, and the consumer drops the second 104 by event id.
- Had we saved "last ack = 104", the restart would start at 105 and 103 would be lost for good, silently. The
  planted bug "save the last ack" was caught by `PublishCheckpointTest`.
- The same pattern handles a failed send: forget everything in flight (`rewind`), resend from line + 1.

### 4. Why do consumers deduplicate on the event id (`seq`) and not on Kafka's offset, and what does that ask of S16?

**What it's asking:** what offsets mean, and what makes a consumer idempotent.

**Background:** An offset is a record's position in one partition, assigned by the broker when the record is
appended. If the publisher sends event 103 twice, the broker stores two records with two different offsets. Both
look new by offset.

**Prayog example:** After the outage in question 3, partition 0 holds `..., 104 @ offset 5001, 104 @ offset 5002`.
A consumer that tracked only offsets would apply trade 104 twice, doubling a position.

**Answer:**
- The event id is assigned once, by the matching engine, and is the same in the journal, on every resend and on
  every partition. That makes it the only stable identity.
- Within one partition event ids only grow, so a consumer can keep, per partition, "the last event id I applied" and
  skip anything at or below it.
- S16 must store that number **in the same database transaction** as the position update. If they were separate:
  - a crash after the position update but before saving the id re-applies the event (double count);
  - a crash the other way round skips it (lost trade).

  One transaction makes "applied" and "remembered as applied" a single fact.
- Committing Kafka offsets is then only an optimisation (where to start reading), not a correctness mechanism.

### 5. How do we know the e2e test proves "no events lost" and not just "the publisher said it caught up"?

**What it's asking:** how to design a test whose pass really means the property holds.

**Background:** A status endpoint reports what the code believes. A test that only reads `lag == 0` trusts the code
under test. An independent check reads the result from the other side.

**Prayog example:** `kafka_check.py`:
1. stops the real Kafka container for 8 s while the simulated traders trade;
2. restarts it and waits until `publishedSeq` passes the event written last during the outage;
3. then, without asking the exchange, reads every partition of the topic with Kafka's own console consumer, collects
   all `seq` values, and requires that every number from 1 to the published seq is there. On the long-running local
   stack that was 849,187 events.

**Answer:**
- The final check uses a different tool (Kafka's consumer) and checks the actual invariant (no gaps in 1..N). A
  publisher that lied about its lag would fail it.
- The Testcontainers test goes further: every copy on the topic must equal the journal's event with the same seq,
  and carry the right key.
- The outage must be long enough to force real failures. The integration test keeps the broker paused until the
  publisher reports send errors, so the failure-and-resend path really runs instead of the producer quietly
  buffering.
- Planted bugs confirm the tests can fail:
  - resume from "now" instead of the checkpoint;
  - ignore the durable line;
  - save the last ack.
- One mistake found along the way: reading the topic "until quiet" never ended, because the live market never goes
  quiet. Reading each partition up to the end offset measured beforehand fixed it, and the full scan of 849k records
  takes about 20 seconds.

## In an interview

"The matching engine never talks to Kafka. A separate publisher tails the fsynced event log, the journal acting as an
outbox, and sends with an idempotent, `acks=all` producer keyed by symbol. It checkpoints the contiguous acknowledged
line, so it resends rather than skips after a crash, and consumers deduplicate by the engine's event id in the same
transaction as their update. We proved it by killing Kafka mid-session in CI and checking every event id 1..N
arrived."
