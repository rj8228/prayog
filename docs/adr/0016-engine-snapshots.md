# 16. Engine snapshots: journaled snapshot points, verified on restore and in the replay check

Date: 2026-10-07

## Status

Accepted

## Context

Recovery (ADR 0007) replays the whole input journal. The live journal held about 1.17 million commands after three
days, and the clock alone adds 864,000 a day, so start-up time grows without limit. The study guide's persistence step
and every production engine use the same answer: load the latest snapshot, then replay only the journal after it.

A snapshot must equal exactly the state a full replay reaches at its input seq. Otherwise recovery from it would
trade on a different book (the failure ADR 0014 already guards against).

## Decisions

1. **A snapshot point is a journaled command, `TakeSnapshot`.** On the matching thread, right after applying it, the
   pipeline copies the engine's state into the command's slot (`EngineState`: session, clock, id counters, rules
   version, disabled accounts, the day's client order IDs, every resting order in queue order). The engine itself
   does nothing with the command, so replay treats it as a no-op. The snapshot's input seq is recorded in the
   journal, not guessed.
2. **Derived state is captured at the same point.** The outbound stage sees the same slot after the journal stage,
   when market data has applied every event up to that seq. There it adds the order tracker (open orders, from which
   the book's levels are rebuilt) and the market statistics (last, open, high, low, volume, recent trades) as JSON.
3. **Written off the order path.** A single `prayog-snapshots` thread encodes and writes the file atomically: temp
   file, fsync, rename. The file is `snapshot-{inputSeq}.snap` beside the journal, as a small binary format with
   magic, version and CRC32C. The newest 3 are kept.
4. **When:** every 5 minutes of wall time (`PRAYOG_SNAPSHOT_EVERY_MINUTES`), on every clean shutdown, and on request
   (`POST /api/v1/ops/snapshot`). The shutdown snapshot makes a normal restart replay almost nothing.
5. **Recovery:**
   - Take the newest snapshot that is readable, not beyond the input journal, and not beyond the event log.
   - Restore the engine and market data from it.
   - Replay only the input after its seq. The setup still comes from record 0 of the input journal.
   - Every replayed event is verified against the event log, as before (ADR 0014). A damaged snapshot is skipped, and
     the fallback is an older snapshot or a full replay: the journal remains the truth.
6. **The replay check verifies snapshots.** `ReplayCheck` (CI and `make e2e`) replays the input once and compares the
   engine state at each snapshot's seq with the file. `make e2e` also requires that the exchange's restart used the
   snapshot taken when it was stopped.

## Testing

- `EngineSnapshotTest` (property, 300 random flows with kill switches and session changes): restore a snapshot taken
  anywhere, run the rest through both engines, and require the same events and the same final state.
- `SnapshotTest`:
  - recovering from a snapshot equals replaying everything (engine state, derived tracker, seqs, sim time), with far
    fewer commands replayed;
  - a snapshot equals the state a replay reaches at its seq;
  - codec round trip, a flipped byte rejected, a damaged newest snapshot falling back to the older one, only the
    newest N kept.
- API: a restart starts from the shutdown snapshot, with the same tickers, book, open orders and trades; a snapshot on
  request.
- Planted bugs caught: restoring without the client order IDs (property test and recovery test); replaying from one
  seq too late (recovery test).

## Trade-offs

- Snapshots copy the engine on the matching thread. The cost is proportional to resting orders (thousands here,
  well under a millisecond) and paid once every 5 minutes.
- Market-data sequence numbers continue from the snapshot, as clients expect after a reconnect.
- The journal is not truncated behind snapshots. Since ADR 0022, segments older than the oldest kept snapshot are
  archived (gzipped, still readable); deleting them is a later decision.
