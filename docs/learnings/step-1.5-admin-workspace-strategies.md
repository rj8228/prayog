# Step 1.5 Admin console, trading workspace, strategies

**Built:**
- **A docs site** on GitHub Pages (MkDocs Material, link-checked on every push).
- **The admin user and console** ([ADR 0011](../adr/0011-admin-console.md)): one-click self-test, including an
  online replay of the live journal; live control of the simulated traders; accounts with cancel-all and the kill
  switch; an event replay viewer; each order's journey.
- **The trading workspace** ([ADR 0012](../adr/0012-trading-workspace-and-strategies.md)):
  - panels you drag, resize and swap;
  - presets, a watchlist, a depth chart, positions and P&L;
  - one-click trading, keyboard shortcuts and explain mode.
- **Six strategies in the browser**, each in its own account with pre-trade risk checks, live P&L and a log.

Guides: [using the market](../guides/using-the-market.md); runbooks [13](../runbook/13-admin-console.md) and
[14](../runbook/14-workspace-and-strategies.md).

## Concepts

- [ ] **Roles are additive.** `admin1` has trader, ops and admin, so the admin trades through exactly the same code
  path as everyone. The admin endpoints sit behind one rule (`hasRole("admin")`), tested with tokens that lack it.
- [ ] **Idempotent provisioning.** `users.sh` creates what is missing and leaves the rest. Running it twice creates
  nothing. A realm import only happens on a fresh database, so existing installs need the API route.
- [ ] **Online replay.**
  - Replay every readable input record into a fresh engine.
  - Compare its events with the recorded event log, but only up to the last seq both have reached.
  - This verifies a running exchange without stopping it.
- [ ] **Read-side tools never touch the writer.** Journeys and replay windows are built by reading the journal files
  on another thread. The matching thread never notices.
- [ ] **Control by versioned state.** The admin changes a versioned state object, and traders poll it and apply each
  change once. A jump has an id, so it is applied once even when the poll repeats.
- [ ] **Drag versus swap.** A grid layout pushes panels aside, which is compaction. Swapping is a different
  operation: each panel takes the other's place and size, kept within its minimum size.
- [ ] **Per-user layout in `localStorage`, guarded.** Storage can be missing or throw (private windows), so every
  read and write is wrapped and the page works without it.
- [ ] **Average-cost P&L.**
  - Buys average into the cost.
  - Selling realizes `(price − average) × shares`.
  - A flip starts a new average at the flip price.
  - Unrealized P&L = `position × (mark − average)`.
- [ ] **Deduplicate by trade id.** The same fill can arrive twice: in the order answer and on the private feed. Count
  each trade once.
- [ ] **Pure strategy, impure runner.** `decide(context) → actions` is deterministic and unit-testable. The runner
  owns time, I/O, risk checks and errors.
- [ ] **Pre-trade risk.** Checks: order size, worst-case position (open orders counted as filled), price distance,
  order rate. Plus a loss limit that cancels everything and stops. Cancels are always allowed because they only
  reduce risk.
- [ ] **One account per strategy.** Separate account ids keep each strategy's orders, fills and P&L apart. Self-trade
  prevention still works across them, because it compares account ids.
- [ ] **Leaving cleanly.** On `pagehide`, a `keepalive` fetch cancels a strategy's orders after the page is gone. On
  start, leftovers from a crash are cancelled.
- [ ] **Marketable limit, not market.** A strategy that wants to trade now sends a limit at the touch: it trades
  like a market order but can never sweep the book at a bad price.
- [ ] **A fat-finger check catches outliers.** The depth chart's x-range is centred on the mid for the same reason:
  one stray order at +10% would otherwise hide the shape of the book.

## Explain-back: questions and model answers

### 1. Why does the self-test compare the live journal only "up to the last seq both reached", and what would a mismatch mean?

**What it's asking:** how to verify a moving system without stopping it.

**Background:** The exchange writes the input journal (commands) and the event log (results) in different stages,
so at any moment the event log may be a few records behind.

**Prayog example:** Suppose 286,288 commands have been replayed and the event log holds events up to seq 1,912,004.
The check fingerprints recorded events with seq ≤ the number the replay produced, and the replay's same prefix.

**Answer:**
- Comparing everything would fail every time the event log lags, and that is normal, not a bug. Comparing the
  common prefix tests exactly the claim we care about: the same inputs give the same outputs.
- A mismatch means determinism is broken. Typical causes:
  - something read the wall clock;
  - iteration order changed (a hash map);
  - floating point crept in;
  - a code change altered matching for the same inputs.

  Replay-based recovery, standbys and audits all rely on this property, so the self-test reports it as a failure
  even though trading continues.

### 2. A strategy sent a buy, got `filled` in the answer, and the private feed delivers the same fill a moment later. How do we avoid counting it twice, and why not just ignore one of the two sources?

**What it's asking:** idempotency across two delivery paths.

**Background:**
- The REST answer includes the fills of an aggressive order.
- The private feed carries every fill, including resting orders filled later by others, which no answer ever
  mentions.
- Either can arrive first.

**Prayog example:** The runner calls `fill(tradeId, …)` from both paths and keeps a set of seen trade ids. The second
arrival is ignored. A unit test calls it twice with trade 1 and checks the position is 20, not 30.

**Answer:**
- Using only the answer would miss passive fills, which is most of a market maker's trading.
- Using only the feed makes the position lag behind the order the strategy just saw fill, and the feed can briefly
  drop.
- Taking both and deduplicating by the exchange's unique trade id is accurate and fast. The same idea drives
  exactly-once processing in Kafka consumers (Step 2): an idempotency key, not hope.

### 3. Why does the risk check count open orders as if they had filled, and why are cancels never refused?

**What it's asking:** worst-case exposure.

**Background:** Position limits exist to bound loss. Resting orders can fill at any moment without asking you.

**Prayog example:** The limit is 200 shares. The position is 100, with 60 more resting to buy. A new buy of 50
could take you to 210 if everything fills, so `vet` refuses it ("could reach a position of 210"). Checking only
the current 100 would allow it.

**Answer:**
- Risk is about what can happen, not what has happened. Counting open orders is the standard "potential position"
  check that brokers and exchanges apply.
- Cancels reduce exposure. Refusing them could trap you exactly when you most need out: when the loss limit fires,
  the runner must be able to cancel everything. So `vet` returns ok for every cancel.

### 4. What does the average-cost method say your P&L is after: buy 10 @ ₹100, buy 10 @ ₹110, sell 5 @ ₹120, with the last price ₹100?

**What it's asking:** realized versus unrealized P&L.

**Background:**
- The average cost of the open shares changes only when you add to the position.
- Closing shares realizes the difference against that average.
- The rest is marked to the last price.

**Answer, worked:**
- **After both buys:** 20 shares at an average of ₹105.
- **After selling 5 at ₹120:** realized = 5 × (120 − 105) = **₹75**, with 15 shares left at an average of ₹105.
- **At a mark of ₹100:** unrealized = 15 × (100 − 105) = **−₹75**.
- **Net P&L:** ₹0.

This is exactly `positions.test.ts`. The browser trade showed the same mechanics: buy 5 at ₹1,664.05, sell 5 at
₹1,662.15, realized = 5 × (−1.90) = −₹9.50. That is the cost of crossing the spread twice, which is what a market
maker earns.

### 5. Why are strategies pure functions run by a separate runner, and why does the runner live in the tab rather than on the server?

**What it's asking:** separation of decision from execution, and where code should run.

**Background:**
- A strategy decides; something else has to deal with time, network, retries, risk checks and accounting.
- Mixing the two makes strategies hard to test and easy to get wrong.

**Prayog example:**
- `momentum.create(...).decide(ctx)` takes prices and a position and returns "buy 20 at the ask". The tests feed
  it a rising series without any network.
- The runner adds the clock, the exchange calls and the risk checks, and is tested with a fake exchange.

**Answer:**
- The pure core makes every strategy testable in milliseconds and safe to change. It also matches how real trading
  systems separate the signal from the execution and risk layers.
- Running in the tab means no user code ever runs on the exchange's servers, which is the safest default for a
  shared simulator, and it needs no new service.
- The costs:
  - it stops when the tab closes (its orders are cancelled on the way out);
  - it slows down in background tabs;
  - its P&L lives only in the tab.

  Server-side bots already exist for anyone who needs them to keep running: the Python SDK.

## In an interview

"I separated strategy logic from execution: strategies are pure functions, and a runner applies pre-trade risk
checks (worst-case position, size, price collars, rate) and a loss-limit kill switch. Fills arrive by two paths and
are deduplicated by trade id. The admin self-test proves determinism on a live system by replaying the journal and
comparing the event-log prefix both have reached."
