# S16–S17 Post-trade and leaderboard

**Built:** `services/post-trade` reads the exchange's event stream into a PostgreSQL ledger ([ADR 0015](../adr/0015-post-trade-ledger-and-leaderboard.md)):
- positions by average cost, realised and unrealised P&L, and simple charges;
- order and fill history;
- a Redis leaderboard ranked by net P&L.

Traders and bots read their own account at `/api/v1/account/*`; the board is public at `/api/v1/leaderboard`.
`make e2e` checks it on the live market, including a restart mid-session.

## Concepts

- [ ] **Idempotent consumer.** Applying the same message twice has the same effect as once. Here a trade's id is the
  primary key of `trades`, and its effects (fills, positions, charges) are applied only when that insert really adds a
  row, in the same transaction.
- [ ] **At-least-once delivery + idempotent effects = exactly-once results.** Kafka may deliver twice; the ledger
  still counts once. This is how most real systems get "exactly once" without distributed transactions.
- [ ] **Why a watermark alone is not enough.** "Skip anything ≤ last applied id per partition" fails if an older
  event ever arrives after a newer one. Natural keys are robust to any order.
- [ ] **One batch, one transaction, then commit offsets.** A crash between the database commit and the offset commit
  only causes redelivery, which idempotency absorbs. The reverse order would lose data.
- [ ] **Never skip a failed batch.** Spring Kafka's default error handler gives up after a few retries. For money,
  wait and retry forever, and alert, rather than drop.
- [ ] **Average cost in integers.** Keep total cost, not a per-share price, so splits stay exact; the leftover
  fraction of a paisa stays with the open position.
- [ ] **Zero-sum reconciliation.** In a closed market, positions and P&L before charges sum to zero across accounts
  at one mark. A single SQL query checks the whole ledger.
- [ ] **Sorted sets.** A Redis ZSET keeps members ordered by score. Top N and rank are O(log n).
- [ ] **Rebuildable views.** Redis is a cache of the ledger: it can be thrown away and rebuilt, and swapped in
  atomically with `RENAME`.
- [ ] **Code generation from DDL.** jOOQ reads the Flyway SQL and generates type-safe tables, so the schema has one
  source.

## Explain-back: questions and model answers

### 1. The publisher resends events after a Kafka outage. Why do positions not double, and why did we not rely only on "last applied event id per partition"?

**What it's asking:** how idempotency is achieved, and the failure case of the simpler design.

**Background:** At-least-once delivery means any event can arrive two or more times. A consumer must make the
second application a no-op.

**Prayog example:** Trade 812 (INFY, Alice buys 10 from Bob) arrives, is applied, and then arrives again after the
publisher rewinds to its checkpoint. The second time, `insert into trades … on conflict do nothing` inserts 0 rows,
so `Ledger.trade` returns before touching Alice's or Bob's positions.

**Answer:**
- The trade id is a natural, unique key assigned once by the engine. Making it the primary key turns "have I seen
  this?" into a database constraint, checked atomically with the update it guards.
- A per-partition watermark ("skip ≤ 812") also works when events arrive in order. But after a failed send, the
  producer may have delivered a later event of a partition while an earlier one failed. The resend then puts the
  earlier one *after* the later one, and a watermark would skip it for ever: a lost trade. The key-based check does
  not depend on order.
- Order status updates use a guard of their own (`last_event_id < this id`), so a late old event cannot undo a newer
  status.
- `LedgerIT` proves it by redelivering the whole 3,000-trade log *shuffled* and requiring identical tables.

### 2. Why is a Kafka batch one database transaction, committed before the Kafka offsets?

**What it's asking:** ordering of commits across two systems without a distributed transaction.

**Background:** The consumer has two pieces of state: the ledger (PostgreSQL) and its position in the topic (Kafka
offsets). They cannot be committed atomically together.

**Prayog example:** A batch of 1,000 events contains 40 trades. The service crashes:
- *after* the database commit but *before* the offset commit → on restart the batch is redelivered, every trade
  conflicts, nothing changes;
- *before* the database commit → nothing was written, and the batch is redelivered and applied.

**Answer:**
- Committing the database first makes every crash point safe, because the worst case is redelivery, which
  idempotency handles. Committing offsets first would make one crash point lose a whole batch.
- One transaction per batch, not per event, means a batch is all or nothing (`PostTradeIT.aBatchIsOneTransaction`
  shows a bad second trade rolling back the first). It is also much faster: one commit and fsync instead of 1,000.
- If the database is down, the error handler retries the same batch every second, forever. Spring Kafka's default
  would skip it after ten tries; for a ledger that means silently missing trades.

### 3. How does the average-cost method stay exact in integer paise, and what does `realised − cost = cash` tell us?

**What it's asking:** money arithmetic without floating point or rounding drift.

**Background:** Average cost means a partial sale realises P&L against the average purchase price. The average is
usually not a whole number of paise.

**Prayog example:** Buy 3 @ ₹100.01 and 1 @ ₹100.00 → 4 shares, total cost 40,003 paise (₹100.0075 each). Sell 1 @ ₹100:
- removed cost = 40,003 × 1 / 4 = 10,000 (truncated), so realised = 10,000 − 10,000 = 0;
- the remaining 3 shares carry cost 30,003;
- sell them @ ₹100: realised = 30,000 − 30,003 = −3.

Total realised = −3 paise = 40,000 − 40,003, exactly.

**Answer:**
- Storing the *total* cost keeps every number an integer. Truncation moves a fraction of a paisa between "realised
  now" and "realised later", but never loses it.
- The identity `realised − open cost = −Σ(signed quantity × price)` (the cash that moved) holds after every fill. The
  property test checks it for random fill sequences. Adding open quantity × mark gives the full mark-to-market P&L.
- That identity is what makes the reconciliation test possible: a naive computation from the trade log (cash plus
  open quantity at the mark) must equal realised + unrealised, to the paisa, for every account.

### 4. Why must the whole market's P&L before charges add up to exactly zero, and how do we use that?

**What it's asking:** an invariant that checks the entire ledger at once.

**Background:** Every trade has a buyer and a seller in the same exchange. Self-trades are prevented, so they are
different accounts. Charges are paid to the exchange or broker, outside the traders.

**Prayog example:** Alice buys 10 INFY from Bob at ₹1,500.00, then INFY trades at ₹1,520.00.
- Alice: +₹200 unrealised.
- Bob: short 10 at ₹1,500 marked at ₹1,520, so −₹200.
- Sum: 0. Net quantity in INFY: +10 − 10 = 0.

**Answer:**
- Money and shares only move between accounts, so at a common mark per symbol the totals cancel exactly. Any non-zero
  total means the ledger lost a fill, double-counted one, or applied one side of a trade without the other.
- `GET /api/v1/post-trade/status` computes both totals in one SQL statement (a consistent snapshot), and
  `posttrade_check.py` requires 0 on the live market, before and after a post-trade restart.
- It complements, not replaces, the per-account reconciliation: a bug that moved money between two accounts would
  still sum to zero, and the per-account test catches it.

### 5. Why is the leaderboard in Redis when PostgreSQL already has the numbers, and how is it kept correct?

**What it's asking:** the role of a derived, rebuildable view.

**Background:** Ranking means sorting every account by net P&L. Doing that in SQL on every page load joins positions
with marks and aggregates per account. A Redis sorted set keeps the order up to date as scores change.

**Prayog example:** After a batch, Alice and Bob traded, and INFY's mark moved. Every INFY holder's unrealised P&L
changed, so they are all re-scored with `ZADD`. `GET /leaderboard` is then `ZREVRANGE 0 19 WITHSCORES`.

**Answer:**
- Redis gives O(log n) updates and reads, and fits the access pattern: many reads, scores changing a few at a time.
- PostgreSQL stays the source of truth. If an update fails, or the set is empty although trades exist, the board is
  rebuilt from the ledger into a temporary key and swapped in with `RENAME`, which is atomic.
- Updates and rebuilds run on the listener thread, one after another, so an old rebuild can never overwrite a newer
  score.
- Scores are doubles, exact for integers up to 2^53 paise, far beyond any P&L here.
- Names: account ids are hashes, so the board shows `username/label` for accounts whose owner has looked themselves
  up, and the id otherwise.

## In an interview

"Post-trade consumes an at-least-once event stream. Trades are applied by natural key in the same transaction as
their effects, so redelivery in any order is harmless, and offsets are committed after the database. Positions use
average cost in exact integer paise. I reconcile two ways: per account against a naive computation from the trade log,
and globally with the zero-sum invariant. The leaderboard is a Redis sorted set, treated as a rebuildable view of the
ledger."
