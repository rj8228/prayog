# S10 to S13 (+ S18 slice) The live market

**Built:**
- **The exchange service** (S10, S11): REST and WebSocket order entry, Keycloak JWT, rate limits, kill switch, a market-data feed of snapshots and numbered deltas, a private feed per account, and recovery from the journal on restart.
- **The Python SDK** (S12) with a `prayog` CLI and a sample bot.
- **Simulated traders** (S13): a market maker plus noise and momentum traders around a hidden fair value.
- **A web market page** (S18 slice): live chart, order book and trades, sign-in, order ticket, open orders and fills.
- **`make e2e`**: the live market checked end to end, including a replay of the live journal.

ADRs [0009](../adr/0009-exchange-service-gateway-market-data.md) and [0010](../adr/0010-simulated-traders-sdk-and-web.md). Guides: [using the market](../guides/using-the-market.md), [building a bot](../bots/README.md). Background pages: [market-data-feeds.html](../overview/market-data-feeds.html), [market-making.html](../overview/market-making.html).

## Concepts

- [ ] **Answer after durable.** The HTTP answer comes from the stage after the journal; a request context rides on the ring slot (never journaled) so that stage knows whom to answer.
- [ ] **Recovery is replay.** On start, replay the input journal into a fresh engine, repair a lagging event log, continue the input sequence. The journal's setup wins over config.
- [ ] **Derived views.** Market data and open orders are rebuilt from events by `OrderTracker`, never read from the single-writer engine; a property test proves they always agree.
- [ ] **Publish whole commands.** An incoming order passes through states that never existed; only the state between commands goes on the feed.
- [ ] **Snapshot then deltas, per-symbol seq.** Gap = resync. The snapshot and first delta must be consecutive (one lock for subscribe and publish).
- [ ] **Slow consumers are disconnected,** not buffered forever and never allowed to slow everyone.
- [ ] **Accounts from tokens.** Account id = hash(subject + label): no table, stable on replay, labels can't escape the subject.
- [ ] **Offline token validation.** JWKS, issuer, audience, expiry; browsers pass the token as a query parameter only for WebSockets.
- [ ] **Rate limit before the ring.** A flood never costs a journal write; `429` and `503` are different stories.
- [ ] **Don't do work on the pipeline thread.** A completed future runs its continuations on the completing thread: hop off (`publishOn`) before building responses.
- [ ] **Market-maker economics.** Spread pays; inventory risk is managed with skew and caps; informed flow (adverse selection) is what the spread must cover.
- [ ] **Never cross yourself.** Move the side stepping away from the market first, or self-trade prevention cancels your own quote.
- [ ] **Reconcile.** Fills can beat the REST answer that names the order; the exchange's open-order list is the truth.
- [ ] **Containers' small print.** `JAVA_TOOL_OPTIONS` rejects module flags (use `JDK_JAVA_OPTIONS`); the JRE image's `sh` is dash; Corepack prompts and hangs a build; orphaned containers keep router names.
- [ ] **A started thread is not a running consumer.** Disruptor's shutdown drains only consumers already running; `start()` now waits for every handler's `onStart`. Found because CI's slower runners closed pipelines before their threads ran (261 of 300 local repeats then reproduced it).
- [ ] **Environment can lie.** A sleeping Mac pauses everything and Docker's VM clock lags after wake; tokens then look expired. Measure before blaming code.
- [ ] **End-to-end correctness, not just liveness.** `make e2e` compares feed-rebuilt books with REST snapshots at the same seq and replays the live journal.

## In an interview

> "Clients only ever hear about durable facts: the HTTP answer and the market-data delta are both produced by the pipeline stage after the journal, and on restart the engine is rebuilt by replaying that journal, then trading continues at the next sequence number."

> "Market data is derived from the engine's events in a separate stage, published only at command boundaries with per-symbol sequence numbers; our end-to-end test rebuilds every book from the live feed and requires it to equal the exchange's snapshot at the same sequence."

> "Our own market maker taught us two classic lessons: move quotes away from the market first or you self-trade, and reconcile against the exchange because fills can arrive before the acknowledgement."

## Explain-back answers

To be added after the explain-back discussion for these sessions (see the handoff).
