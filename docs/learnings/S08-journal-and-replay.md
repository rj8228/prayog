# S8 Journal and deterministic replay

**Built:**
- **The codec:** an SBE schema for every command, every event and the engine setup, with Java codecs generated at build time.
- **The journal:** a segmented file journal with CRC-protected records and torn-write recovery.
- **Stage 1:** the journal runs as the first pipeline stage, with one fsync per batch.
- **Replay:** a tool that rebuilds a session from the input journal and compares SHA-256 checksums. It runs in CI as `make replay-check`.

[ADR 0007](../adr/0007-journal-sbe-and-replay.md). Background pages: [sbe.html](../overview/sbe.html), [fix-protocol.html](../overview/fix-protocol.html), [journaling.html](../overview/journaling.html).

## Concepts

- [ ] **Write-ahead / input journal.** Record every input before acting on its results. Inputs + a deterministic engine = the whole state, so the journal *is* the database.
- [ ] **Event sourcing.** State is never stored directly; it is rebuilt by replaying events (or here, commands). The event log is a derived copy.
- [ ] **fsync and the page cache.** `write()` only reaches the operating system's memory. `force()` waits for the disk. Until then a power cut loses the data.
- [ ] **Group commit.** One fsync covers a whole Disruptor batch (`endOfBatch`). Throughput scales with load; a quiet market still gets one flush per command.
- [ ] **Ack after durable.** Responses and market data are later stages, so no client ever hears about something a crash could erase.
- [ ] **Torn writes and CRC32C.** A crash can stop a write mid-record. A length running past the end of the file or a checksum mismatch detects it. Only the unflushed tail can be torn, so it is safe to cut.
- [ ] **Torn tail vs. corruption.** Damage in the last record is a crash; damage anywhere else means the disk lost flushed data, so stop.
- [ ] **Truncate, don't just skip.** Writing over a torn tail can leave junk behind a shorter new record.
- [ ] **Segments.** Bounded files that roll over, so old history can be archived whole and recovery scans only the last one.
- [ ] **SBE.** Fixed-offset binary messages read and written in place by flyweights; no parsing, no allocation, byte-stable, versioned schema.
- [ ] **Stable encodings.** Explicit enum numbers, add-only fields, a golden-bytes test. A codec change must never silently change old journals.
- [ ] **Self-describing journals.** The engine setup is record 0, so replay needs no config files (config drift is caught, not hidden).
- [ ] **Determinism check.** SHA-256 over the replayed events equals the recorded one. It proves no clock reads, randomness or hash-order output; it does not prove the rules are right.
- [ ] **Test the test's workload.** The first workload was 79% rejects. Assert the event mix so a weak workload fails.
- [ ] **JPMS exports.** Agrona 2.x needs `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED` to reach the JDK's internal Unsafe.

## In an interview

> "Every command goes through a single-writer pipeline whose first stage is a write-ahead journal: SBE-encoded, CRC-protected records in segment files, one fsync per Disruptor batch, so clients are only acknowledged once their order is durable."

> "Because the engine is deterministic, the input journal is the source of truth. CI records a 50k-command multi-threaded session, replays it into a fresh engine and requires the same SHA-256 over the event log; when we planted a wall-clock read, it pointed at the first differing trade."

## Explain-back answers

1. **Why wait for fsync, and why once per batch?** A client told "filled" must never lose that fill in a crash, so responses wait for the disk. fsync costs 0.1 to 10 ms, so one per command would cap throughput. Group commit flushes once per Disruptor batch: under load one flush covers hundreds of commands; when quiet, latency is one flush.
2. **Torn write vs. corruption, and why truncate?** Only the last, unflushed record can be torn (bad length or CRC); it was never acknowledged, so cut it. The same damage in an earlier segment means flushed data was lost: stop. Truncating matters because a shorter new record written over the torn bytes would leave junk behind it. The tests first missed this, so they now check the file size at every possible cut.
3. **Why is the input journal the source of truth, with the setup as record 0?** Commands plus the starting setup determine every event, so the event log can be rebuilt, which is why the input journal is flushed first. Putting the setup in the journal makes replay independent of config files that may have changed. The drift test proves the check catches a mismatch.
4. **Why SBE, and what keeps the bytes stable?** It is the exchange-industry binary standard: fixed offsets, zero allocation, same message gives same bytes, versioned schema. Stability comes from explicit enum numbers, add-only fields, never renumbering ids, and a golden-bytes test.
5. **What does the checksum prove?** That the engine is a pure function of the journal: no clocks, randomness or hash-order output (the planted `nanoTime()` bug was caught at event #49). It does not prove matching rules are correct, nor that re-running bots reproduces a session. The workload itself had to be checked: an event-mix assertion stops a test that only exercises rejects.
