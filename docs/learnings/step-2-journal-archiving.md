# Journal archiving

**Built:** finished journal segments are gzipped into `journal/archive/` after each snapshot and every reader still
sees the whole history ([ADR 0022](../adr/0022-journal-archiving.md)). On the live journal: 2.6x (input) and 4.8x
(events) smaller; reading archived history is 6-9x slower ([benchmarks](../benchmarks.md)).

## Concepts

- [ ] **Segmented logs.** A log split into fixed-size files can be managed whole-file at a time: closed files never
  change, so they can be compressed, moved or deleted without touching the file being written. Kafka, databases'
  write-ahead logs and this journal all do it.
- [ ] **Retention vs compaction vs archiving.** Retention deletes old segments; compaction keeps the latest value per
  key; archiving keeps everything but moves it to cheaper storage. An event-sourced system whose replay is a
  correctness check can only archive.
- [ ] **Safe points.** A segment may leave the hot path only when no fast reader still needs it: here, older than the
  oldest kept snapshot (restart) and below the Kafka publisher's checkpoint (the outbox reader).
- [ ] **Crash-safe file replacement.** Write a temp file, fsync it, verify it, rename it (atomic), fsync the directory,
  then delete the original. Every crash point leaves either the old state or a recoverable mix.
- [ ] **Location transparency.** Readers ask for "the history" and the storage layer decides where each piece lives,
  so archiving changed one class (`Segments`) instead of every reader.
- [ ] **Memory is part of correctness.** A background job that is correct on a laptop can still starve a service in a
  small container (see the incident in the ADR): stream large files, bound buffers.

## Explain-back: questions and model answers

### 1. Why archive instead of deleting segments older than the oldest snapshot?

**Answer:** Deleting would make the disk stop growing, but three things depend on the full history. The replay check
replays from seq 1 and compares checksums: that is the proof of determinism (an engine invariant). Input record 0
holds the engine setup, which recovery reads even when it starts from a snapshot. And a full replay is the fallback
when no snapshot is usable. Archiving keeps all three working and still cuts disk use 2.6-4.8x. Deleting is a separate
decision that would need the replay check to start from a kept snapshot instead.

### 2. Why is the event log's safe point the lower of the oldest snapshot's event seq and the Kafka checkpoint?

**Answer:** Two fast readers use the event log. Recovery verifies every event it replays after a snapshot against the
log, from that snapshot's event seq onward. The Kafka publisher resends from its checkpoint after an outage or restart.
Archiving below both keeps both on live segments. If Kafka is down for a long time the checkpoint stops moving, so the
event log simply stays live and larger, which is safe. Archiving past it would still be correct (readers can read the
archive) but every catch-up would gunzip 64 MiB segments.

### 3. The archiver crashes after renaming the `.gz` but before deleting the original. What do readers see, and what happens next?

**Answer:** Both files exist for the same first seq. `Segments.list` keys segments by first seq and lists the live file,
so every record is read once. On the next run the archiver finds the archive already there, verifies it byte for byte
against the original again (it might be from a different crash), and only then deletes the original. A crash before
the rename leaves only a `.tmp`, which readers ignore and the next run deletes.

### 4. What went wrong in the first `make e2e`, and why didn't the unit tests catch it?

**Answer:** The verification read the 64 MiB original and the 64 MiB gunzipped copy whole, plus `readAllBytes`'
growing buffers, inside a 576 MiB heap the exchange was already using. The JVM spent its time in GC, stopped answering
Prometheus, the shutdown snapshot queued behind the stuck archiver, and Docker killed the container after the grace
period. The unit tests used 200-byte segments in a large test heap, so memory never mattered. The fix streams the
comparison in 64 KiB chunks, gunzips into an exact-size buffer, and skips archiving once shutdown starts; a 96 MiB heap
run on the real segments proves it (the old code throws `OutOfMemoryError`).

### 5. Why run the archiver on the snapshot thread rather than its own scheduled thread?

**Answer:** Three reasons. It runs right after the event that makes new segments archivable (a new snapshot moves the
oldest kept one forward). One thread means snapshots and archiving never run at the same time, so the oldest kept
snapshot can't be deleted while it is being read. And it stays off the order path. The cost is that a long archive
delays the next snapshot, which is why archiving stops when shutdown begins: the shutdown snapshot is what makes the
next start fast.
