# 7. Journal, SBE codec and deterministic replay

Date: 2026-10-04

## Status

Accepted

## Context

S8 closes M2: an append-only journal behind an interface, a replay tool and a checksum test in CI (BUILD_PLAN 16.1 #14, #15). Clients must get answers only after the journal is on disk (ADR 0006). The build plan named a custom binary record format; at the start of S8 we chose a standard codec for the payload instead.

## Decisions

1. **Payload codec: SBE (Simple Binary Encoding), the FIX Trading Community standard.** The schema is `exchange-core/src/main/resources/sbe/journal-schema.xml`; `sbe-tool` generates flyweight encoders and decoders at build time (exec-maven-plugin, then build-helper adds the sources). Reasons: fixed field offsets give byte-identical output for equal messages (the checksum depends on it), encoding allocates nothing, and the schema carries an explicit version. JSON has no canonical byte form; Protobuf does not promise stable bytes across library versions. Kafka keeps JSON (BUILD_PLAN 8); SBE is only the journal's format.
2. **Stable bytes by rule:** enum values are numbered in the schema (never the Java ordinal); message ids and field order never change; new fields go at the end with a schema version bump. A golden-bytes test pins one message's exact hex. The SBE enums are named `...Code` to avoid clashing with the contract enums.
3. **Agrona 2.x requires `--add-exports java.base/jdk.internal.misc=ALL-UNNAMED`.** Set for Surefire and Failsafe in the parent POM (`agrona.jvm.args`); the app and any tool JVM need it too.
4. **Record framing: `[length][seq][payload][crc32c]`, little-endian.** The planned `[type]` field is dropped: the SBE header inside the payload already names the message (template id) and its schema version. CRC32C (JDK `java.util.zip.CRC32C`, hardware-accelerated) covers length, seq and payload.
5. **Segment files** `{name}-{firstSeq:020d}.seg`, 64 MiB by default, each with a 16-byte header (magic `PRYJ`, format version, first seq). A new segment is fsynced and its directory synced (best effort; macOS cannot fsync a directory through a channel).
6. **Two journals in one directory.** `input`: record 0 is the `EngineSetup` (instruments, schedule), then every command at its input sequence. `events`: every event at its event seq. The input journal is the source of truth and is flushed first; the event log can be rebuilt from it.
7. **The setup goes in the journal, not in config.** A replay needs nothing but the journal directory, so config changes after the fact cannot break it.
8. **Group commit.** `JournalHandler` is pipeline stage 1. It appends into a 1 MiB buffer and on `endOfBatch` writes and calls `force(false)` (fdatasync) on both journals. Later stages see a slot only after that.
9. **Recovery on open.** Only the last record of the last segment can be torn (it was never flushed, so no one was told about it). It is detected by a length past the end of the file or a CRC mismatch, and truncated, so later appends cannot leave junk behind. A bad record in any earlier segment is corruption: open fails. A segment shorter than its header (crash during creation) is deleted.
10. **Replay and checksum.** `Replay` feeds the input journal into a fresh engine on one thread and computes SHA-256 over each event record's length, seq and payload (no segment headers, so segmentation does not matter). `Replay.check` compares this with the recorded log and, on a mismatch, names the first differing event. `ReplayCheck` is the command-line form (exit 0 match, 1 mismatch, 2 error). A gap in input seqs fails the replay.
11. **Resuming after a restart is not supported yet.** `JournalHandler` refuses a directory that already holds a session. Resuming means replaying the journal into the engine and continuing the sequence; it comes with the app wiring (S10).

## Testing

- **Codec:** jqwik round trips for every command, event and setup (arbitrary Unicode strings, any longs); a golden-bytes test; every Java enum value has a wire code; foreign schema ids are rejected.
- **Journal:** reads across segments; oversized records; reopen and continue; a cut at every byte of the last record recovers to the previous record *and* shrinks the file; a flipped byte in the last record is cut; the same in an earlier segment is corruption; a half-created segment is discarded.
- **Stage 1:** a later stage never sees a slot before its command and events were flushed; fewer flushes than commands; the journals decode to exactly what the pipeline processed.
- **S8 acceptance (`make replay-check`, own CI job):** 4 producer threads and a clock thread submit 51,001 commands (about 69k events). The clock crosses the open, the close with expiry and the next open; an ops halt happens; producers cancel and modify their own orders, learned from a stage-2 "acks" handler. Replay matches the recorded SHA-256. The test also checks the event mix, so a workload that only produced rejects would fail.
- **Planted bugs, all caught:** strings decoded out of order; no CRC check; no truncate on recovery (the first version of the tests missed it; the file-size check was added); the engine reading `System.nanoTime()` for a trade (the replay check named event #49). A config-drift test (live bands differ from journaled setup) is part of the suite.

## Trade-offs

- A build-time code generator and a JVM flag, in exchange for a standard, zero-allocation, versioned format.
- One fsync per batch puts disk latency on the response path when the market is quiet; that is the price of never acknowledging an order a crash could erase.
- The input journal grows with every clock tick (10 per second). Archiving old segments is left for later.
- On a mismatch, the first-difference search holds both event logs in memory. It is a diagnostic path, used only after a failure.
