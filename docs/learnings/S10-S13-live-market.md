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

1. **Why does the HTTP answer come from the stage after the journal, and how does it find its caller?** Stages run matching → journal → outbound, so whatever outbound says is already on disk; a client is never told about a trade a crash could erase. The gateway attaches the pending HTTP response to the ring slot as a *context* (never journaled, never seen by the engine); the outbound stage completes it with exactly that command's events. Completing a future runs its continuations on the completing thread, so the gateway hops to a worker pool (`publishOn`); otherwise the pipeline thread would build every response.
2. **What happens on restart?** The input journal is replayed into a fresh engine: same resting orders, queue positions and next ids. Events missing from the event log (a crash between the two flushes) are appended; the market-data view is rebuilt from the same replayed events; the pipeline continues at the next input sequence; the journal's setup wins over current config. Proved by the API test that restarts and finds its open orders, and by `make e2e` replaying the live journal (~110k commands) to the identical checksum.
3. **Why derive market data from events instead of reading the engine's book?** The engine belongs to one thread; reading it elsewhere would need locks on the order path. `OrderTracker` rebuilds depth from events and publishes only between commands (an incoming order passes through states that never existed). A property test (60 sessions × 600 commands) checks it equals the engine's book after every command; `make e2e` checks feed-rebuilt books equal REST snapshots at the same `seq`.
4. **How does a client know its book is right?** Every per-symbol message has `seq` exactly one higher than the last. Snapshot and subscription happen under the same lock as publishing, so the snapshot and the first delta are consecutive. A jump in `seq` means resubscribe for a fresh snapshot (`SequenceGap` in the SDK, "resyncing" in the web app). A client more than 10,000 messages behind is disconnected rather than slowing everyone.
5. **What makes the market maker work, and what broke it?** It earns the spread, controls inventory with skew and a cap, and widens the spread to cover informed flow. Two traps found live: moving bids up before asks crossed its own quotes (self-trade prevention cancelled them 798 times), fixed by moving the away-side first; and fills arriving before the response that names the order, fixed by reconciling against the exchange's open orders every 2 s.

## Lessons from running it for real

| Found by | Problem | Fix |
|---|---|---|
| Live run, metrics | Market maker self-crossing, one-sided book | Move away-side quotes first; skip quotes that would reach own orders |
| Live run, ladder | Ghost orders after early fills | Reconcile with `GET /orders` every 2 s |
| CI (slower runners) | Pipeline closed before consumer threads ran lost queued commands (261/300 locally once reproduced) | `start()` waits for every handler's `onStart` |
| Log thread name | Responses built on the outbound pipeline thread | `publishOn(Schedulers.parallel())` |
| Container start | `JAVA_TOOL_OPTIONS` rejects `--add-exports`; JRE image `sh` is dash | `JDK_JAVA_OPTIONS`; health check under `bash` |
| Hung image build | Corepack download prompt | `COREPACK_ENABLE_DOWNLOAD_PROMPT=0` |
| 404 on `api.` | Orphaned container still claiming the router name | `make up --remove-orphans` |
| CI only | Older Compose fails `up --wait` on a finished one-shot | `service_completed_successfully` dependency |
| Browser 401s | Mac slept; Docker VM clock lagged; tokens minted already old | Renew once on 401; runbook note; `caffeinate` for long runs |
| My own commit | `cmd \| tail` hid a failing pytest exit code; committed red | Commit only when the test command itself exits 0 |
