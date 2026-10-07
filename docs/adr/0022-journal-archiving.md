# 22. Journal archiving: gzip finished segments, keep every record

Date: 2026-10-08

## Status

Accepted

## Context

The input journal grows by about 864,000 clock-tick records a day plus every order; after three days of sessions the
live volume held two 64 MiB segments per journal and nothing ever left the directory. Snapshots (ADR 0016) made restart
fast, so old segments are rarely read, but four readers still use history: recovery (input record 0 holds the setup;
a full replay is the fallback when no snapshot is usable), the replay check in CI and `make e2e` (replays from seq 1),
the Kafka publisher (reads the event log from its checkpoint) and the admin views (order journey, replay window).

## Options

1. **Delete segments behind the oldest snapshot.** Smallest disk; breaks the full replay and the determinism check,
   and the setup record would need a new home.
2. **Archive: compress finished segments and keep them, readers see one history.** Disk shrinks, every reader keeps
   working, nothing is lost.
3. **Leave it.** Disk grows without limit.

## Decision

Option 2.

1. `JournalArchiver.archive(dir, name, upToSeq)` gzips each **finished** segment (one with a later segment after it)
   whose records are all at or below `upToSeq` into `journal/archive/{name}-{firstSeq}.seg.gz`, then deletes the
   original. The bound needs no scan: the next segment's first seq minus one.
2. **Crash safety:** write `.tmp`, fsync, gunzip and compare byte for byte with the original, rename, fsync the
   directory, delete the original. A leftover `.tmp` is ignored by readers and deleted next run; a crash after the
   rename leaves both files, readers list the segment once (the live one), and the next run checks the archive again
   before deleting the original. An archive that differs is an error and the original stays.
3. **One history for readers.** `Segments.list` returns archived then live segments in seq order; a segment is
   memory-mapped when live and gunzipped into memory when archived. A segment archived between listing and reading is
   found in the archive. `FileJournal` scans only live segments on open (start-up no longer reads old history).
4. **Safe point** (exchange app): input up to the oldest kept snapshot; events up to the lower of that snapshot's event
   seq and the Kafka publisher's checkpoint. Restart from any kept snapshot and the publisher then read live segments
   only.
5. **When:** on the `prayog-snapshots` thread right after each snapshot is written (off the order path), and on request
   (`POST /api/v1/ops/archive`). Disk use is in the ops status, on the ops page and in Prometheus
   (`prayog_journal_live_bytes`, `prayog_journal_archived_bytes`).

## Testing

- `JournalArchiverTest` (12): only finished segments at or below the safe seq; never the active one; readers and the
  tailer see the same history, including starting inside an archived segment; the journal reopens and appends; crash
  before and after the rename; a differing archive refused; a segment archived between list and read; a damaged
  archive is an error.
- `SnapshotTest`: recovery from a snapshot and a full replay give the same state after archiving; the snapshot check
  still matches.
- `ReplayDeterminismTest`: the replay check's checksums are identical before and after archiving.
- Planted bug (readers ignore the archive): 11 tests fail.
- Measured on the live journal (docs/benchmarks.md): 2.6x (input) and 4.8x (events) smaller; about 3 s to archive a
  64 MiB segment; full reads of archived history 6-9x slower.

## Trade-offs

- Disk still grows, 2.6-4.8x more slowly. Deleting history is a separate decision: it needs the replay check to start
  from a kept snapshot and gives up replay from seq 1.
- An archived segment is held in memory while it is read (64 MiB by default); readers read one segment at a time.
- gzip from the JDK, not zstd: no new dependency; zstd would compress faster and slightly better.
- The event log can only be archived once Kafka has acknowledged it; a long Kafka outage keeps it live (correct, just
  larger).
