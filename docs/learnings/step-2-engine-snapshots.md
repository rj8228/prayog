# Step 2 Engine snapshots

**Built:** the exchange saves its whole state every 5 minutes and at shutdown, and recovery starts from the newest
snapshot instead of replaying the whole journal ([ADR 0016](../adr/0016-engine-snapshots.md)). The replay check
proves every snapshot equals the state a full replay reaches.

## Concepts

- [ ] **Snapshot + log = state.** State at seq N = snapshot at seq S (S ≤ N) + replay of the log from S+1 to N. The
  log alone is enough but slow; the snapshot is an optimisation, never the truth.
- [ ] **Consistent cut.** Every part of a snapshot must describe the same instant. Here the instant is an input seq:
  the engine copy is taken on the matching thread right after the `TakeSnapshot` command, and the derived state when
  the outbound stage reaches that same command.
- [ ] **Snapshot point in the log.** Journaling the request makes the cut point part of the history, so replay knows
  exactly where every snapshot belongs and can verify it.
- [ ] **Off the critical path.** Copy cheaply on the owning thread, encode and fsync elsewhere.
- [ ] **Atomic file replace.** Write a temp file, fsync it, then rename. A crash leaves the old snapshot or the new
  one, never half of one. A CRC catches anything else.
- [ ] **Fallbacks.** Damaged newest snapshot → older snapshot → full replay. Each step is slower but always correct.
- [ ] **Equivalence testing.** "Restore and continue" must equal "never stopped": a property test over random flows
  checks exactly that.

## Explain-back: questions and model answers

### 1. Why is the snapshot request a journaled command instead of the exchange just dumping its state on a timer?

**What it's asking:** how to get a consistent cut in a pipelined system without stopping it.

**Background:** The engine runs on the matching thread. Market data is updated later, on the outbound stage. At any
wall-clock instant the two are at different input seqs. A timer thread reading both would capture a book from seq
1,000,500 and open orders from seq 1,000,480: a state that never existed.

**Prayog example:** `TakeSnapshot` is input seq 1,166,240. The matching thread copies the engine right after it. The
outbound stage processes the same slot a moment later, when market data has applied exactly the events up to that seq,
and adds its own state.

**Answer:**
- The command gives both stages the same marker in their single stream of work, so each copies its state at the
  same logical instant without a lock, and without pausing trading.
- Being journaled, the snapshot's seq is part of the history. The replay check finds that seq during a full replay
  and compares the engine state with the file.
- The engine treats the command as a no-op, so it changes no events, and replays (with or without snapshots) still
  produce byte-identical event logs.

### 2. What exactly goes into the engine snapshot, and what would break if one piece were missing?

**What it's asking:** what "state" means for a deterministic engine.

**Answer:** Everything a future command's outcome can depend on:
- **Resting orders in queue order.** Without the order within a price level, time priority would change and the
  next trade would hit a different order.
- **ID counters** (next event, order and trade id). Without them, new events would reuse ids, and post-trade would
  skip them as duplicates.
- **Session state, sim time, the "ticked" flag.** These decide whether orders are accepted and when the day rolls.
- **Disabled accounts.** Without them, the kill switch would be lifted by a restart.
- **The day's client order IDs and the rules version.** Without them, a retry after a restart would create a
  duplicate order. The planted bug "restore without client order IDs" was caught by both the property test and the
  recovery test.

Not included: instruments and schedule. They come from the journal's setup record, so the snapshot cannot disagree
with them.

### 3. How do the tests show that recovering from a snapshot is the same as never having stopped?

**Answer:**
- **Property test:** for 300 random flows (orders, cancels, modifies, duplicates, kill switches, session changes),
  snapshot at a random point and restore into a fresh engine. Feed the rest of the flow to both engines. Their
  events must be identical, and so must their final states.
- **Recovery test:** a pipelined session with two snapshots mid-run. Recovering from the newest must equal a full
  replay: same engine state, same derived open orders and book, same last seqs and sim time, with fewer commands
  replayed.
- **Replay check:** the live journal's snapshots are compared with a full replay in CI and `make e2e`.
- **API test:** after a restart, tickers, the book, open orders and trades are identical, and the status shows the
  recovery started from the shutdown snapshot.

### 4. A snapshot file is half-written when the machine loses power, or a bit flips on disk. What happens at the next start?

**Answer:**
- The writer uses temp file → fsync → rename. Rename is atomic on one file system, so the real file name points at
  either the previous complete snapshot or the new complete one. A half-written temp file is never read.
- If a complete file is later damaged, the CRC32C check fails, `Snapshot.latest` skips it and tries the next older
  one, and with none left recovery replays the whole journal. Each fallback is slower but correct, because the
  journal is still the source of truth.
- After any of these, recovery still verifies every replayed event against the event log. A snapshot that decoded
  but was wrong would therefore stop the start instead of trading on bad state.

### 5. Why keep the market statistics in the snapshot as JSON, and why is that safe?

**What it's asking:** separating engine state (must be exact) from derived display state.

**Answer:**
- Market data (last price, day high and low, recent trades for the chart) is derived from events, so after a restart
  it must be rebuilt. Without a snapshot that meant replaying every event; with one it is simply loaded.
- It belongs to the application layer, not the engine, so the core snapshot carries it as opaque bytes. JSON is
  readable and easy to evolve.
- It is safe because the outbound stage captures it at the same seq as the engine copy. And if the bytes are
  unreadable, the runtime skips the snapshot and replays everything, rather than show wrong statistics.

## In an interview

"Recovery is snapshot plus log. A snapshot is a journaled marker command: the matching thread copies engine state
right after it, and the downstream stage adds derived state at the same input seq, so the cut is consistent without
stopping trading. Files are written atomically off the hot path with a CRC, with fallbacks to older snapshots or a full
replay. I prove equivalence with a property test (restore and continue equals never stopped), and the replay check
verifies every snapshot against a full replay."
