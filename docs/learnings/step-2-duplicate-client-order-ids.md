# Step 2 Duplicate client order IDs

**Built:** the engine refuses a repeated client order ID per account per trading day, so a client can retry after a
timeout without risking a second order ([ADR 0014](../adr/0014-duplicate-client-order-ids.md)).

## Concepts

- [ ] **Idempotency key.** An ID chosen by the client that makes a request safe to repeat: the server does it at most
  once. FIX calls it `ClOrdID`; payment APIs call it an idempotency key.
- [ ] **The two-generals problem in miniature.** After a timeout the client cannot know whether the server acted. Only
  a repeat that the server can recognise turns "maybe twice" into "exactly once".
- [ ] **Scope decides cost.** Unique forever needs unbounded memory. Unique per account per day, bounded to the last
  10,000, is cheap and still covers every realistic retry.
- [ ] **Rules live where replay sees them.** Putting the check in the engine means the journal and replay reproduce
  it. A gateway-only check would vanish on restart and could not be replayed.
- [ ] **Changing rules versus replaying history.** A new rule changes what old inputs produce. That is safe only if
  no old input triggers it, which can be proved by replaying the real journal. Otherwise the rule needs a version
  recorded in the journal.
- [ ] **Test oracles must learn new rules too.** The reference matcher had to model the rule, otherwise the property
  test would have rejected the real engine.

## Explain-back: questions and model answers

### 1. A bot sends an order, the connection drops before the answer arrives, and it sends the same order again with the same `clientOrderId`. What happens in each case?

**What it's asking:** what the rule guarantees, case by case.

**Background:** The gateway passes `clientOrderId` into the `NewOrder` command. The engine checks it against the
account's IDs for today before accepting.

**Prayog example:** The bot sends `buy 10 INFY @ 1,490.00, clientOrderId = b-77`.

**Answer:**
- **The first attempt never arrived:** the retry is the first time the engine sees `b-77`. It is accepted normally.
- **The first attempt arrived and was accepted:** the retry is rejected with `DUPLICATE_CLIENT_ORDER_ID`. There is
  only one order. The bot can confirm with `GET /orders`, which shows it.
- **The first attempt arrived but was rejected (say, the session was closed):** `b-77` was never recorded, so the
  retry is judged on its own merits.

In every case the bot ends with at most one order. That is the point.

### 2. Why is the check in the matching engine and not in the gateway, which sees the request first?

**What it's asking:** where state must live so that it survives restarts and replay.

**Background:** The gateway is stateless apart from rate limits. The engine's state is rebuilt from the journal on
every start, and replay must give byte-identical events.

**Prayog example:** The exchange restarts between the first attempt and the retry. A gateway-side set of IDs would be
empty after the restart, so the retry would create a second order. The engine rebuilds its set by replaying the
journal, so it still knows `b-77`.

**Answer:**
- Only engine state survives restarts by construction (the journal is replayed), and only engine decisions are
  journaled, so recovery and audit see the same outcome.
- The check must be atomic with accepting the order. On the single matching thread it is, for free. In the gateway,
  two concurrent requests with the same ID could both pass a check and both be accepted.

### 3. Why are the IDs cleared at the close, and why remember only the last 10,000 per account?

**What it's asking:** how to bound the memory of an idempotency mechanism without losing what matters.

**Background:** The simulated traders send hundreds of thousands of orders a day. Keeping every ID forever would grow
the heap without limit and slow down recovery.

**Prayog example:** The market maker sends about 50 orders a second. Ten thousand IDs cover about 200 seconds of its
traffic, while a retry happens within a few seconds of the timeout.

**Answer:**
- **The day boundary** is natural: every order expires at the close, so an ID from yesterday cannot refer to a live
  order. FIX uses the same per-day scope.
- **The 10,000 bound** caps memory per account at a few hundred KB, whatever the day's volume. It only fails if
  10,000 orders from the same account arrive between an attempt and its retry, which a well-behaved client never
  does.
- Eviction is first in, first out, using an `ArrayDeque` beside a `HashSet`. That keeps it deterministic: the same
  inputs always evict the same IDs.

### 4. Adding a rule changes how the engine treats old inputs. How did we know the live journal still replays identically?

**What it's asking:** the risk a rule change poses to deterministic replay.

**Background:** Recovery and `make e2e` replay the input journal with the *current* engine code and compare against
the recorded events. A rule that would have rejected an order that was accepted back then makes the replay
diverge, and recovery would fail.

**Prayog example:** If the simulated traders had ever reused an ID, the old journal would hold two accepted orders
with that ID. The new engine would reject the second, every later order ID and event seq would shift, and the
checksum would differ.

**Answer:**
- First, reasoning: every client generated random UUIDs, so no duplicate could exist.
- Then proof: the replay of the live journal, about 1.1 million commands, matched byte for byte after the change.
- Had it not matched, the right fix would be a **rules version** stored in `EngineSetup` (record 0 of the journal):
  old journals replay with old rules, and new sessions use new ones. Real exchanges do the same when they change
  matching behaviour on a given date.

### 5. How do the tests show the rule is right, and not just present?

**What it's asking:** the testing approach for a rule with several edge cases.

**Answer:**
- **Example tests** pin each edge case: per account, rejected IDs stay free, used after fill or cancel, cleared by a
  new day, and the bound evicting oldest first.
- **The property test** now draws client IDs from a small pool (1-40 per account), so random flows naturally contain
  duplicates mixed with fills, cancels and modifies. The naive reference matcher models the rule with a plain list,
  and the real engine must emit exactly the same events.
- **A planted bug** (forgetting to clear at the close) failed both the example test and the property test.
- **Over HTTP**, an API test sends the same order twice and checks the second is rejected and only one order is open.

## In an interview

"Client order IDs are the idempotency key for order entry. The matching engine, not the gateway, rejects a duplicate
per account per trading day, so the rule is atomic, journaled and replayed. Memory is bounded by a per-account FIFO
window. Because rule changes alter how old journals replay, I proved the change safe by replaying the live journal;
otherwise it would need a rules version in the journal header."
