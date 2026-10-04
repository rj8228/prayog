# 9. Exchange service: gateway, market data and recovery

Date: 2026-10-04

## Status

Accepted

## Context

S10 (gateway) and S11 (market data) turn the engine library into a running service: REST and WebSocket order entry
with Keycloak tokens, rate limits and a kill switch; a market-data feed of snapshots and numbered deltas that a client
can rebuild exactly. The service must recover after a restart (ADR 0007 left that to S10) and answer only after the
journal (ADR 0006).

## Decisions

1. **One Spring Boot 4 WebFlux process** (`exchange-app`): gateway, sequencer, engine, journal and publishers in one
   JVM, 1 shard (BUILD_PLAN 16.2 #12, #17). The pipeline is matching → journal → outbound; the outbound stage answers
   callers and publishes market data and private updates, so everything it says is already durable.
2. **Request context on ring slots** (never journaled): a producer attaches the pending HTTP response; the outbound
   stage completes it with exactly the events that command produced. The engine and journal never see it.
3. **Restart = replay.** If the journal holds a session, `JournalRecovery` replays the input journal into a fresh
   engine (and the market-data hub), appends any events missing from the event log, and the pipeline continues the
   input sequence. The setup in the journal wins over current configuration. The sim clock resumes at the last
   journaled tick.
4. **Market data is derived from events, not read from the engine.** `OrderTracker` (exchange-core) rebuilds depth
   and open orders from events and reports only levels changed by a whole command. A property test checks it equals
   the engine's book after every command; the engine emits no `BookUpdate`. No other thread ever reads engine state.
5. **Feed protocol:** per symbol, a `snapshot` then `trade` and `book` messages with `seq` +1 each; `session`
   messages; `heartbeat` every 5 s. Subscription and publication share one lock, so a subscriber's snapshot and the
   first delta it receives are consecutive. A subscriber more than 10,000 messages behind is disconnected rather than
   slowing the stage.
6. **Accounts from tokens:** account id = first 63 bits of SHA-256(subject + "/" + label), with the label from
   `X-Prayog-Account` (default `main`). No account table; stable across restarts and replays; a label can only name
   accounts under the caller's own subject.
7. **Tokens checked offline:** JWKS from Keycloak (inside Docker: `keycloak:8080`), issuer pinned to
   `http://auth.prayog.localhost/realms/prayog`, audience `prayog-api`, realm roles from `realm_access.roles`.
   Browsers may pass the token as `?access_token=` on the private WebSocket only.
8. **Roles:** public market data; `trader`/`bot` trade; `ops` runs session, clock and kill switches.
9. **Rate limits:** token bucket per account in the gateway (20/s, burst 40; bots 500/s, burst 1,000), checked before
   the ring, so floods never cost a journal write. `503` when the ring is full (`trySubmit`).
10. **Clock housekeeping:** with `autoNextDay`, once sim time has been outside trading hours for 30 s of wall time,
    the clock jumps to the next open (`SimClock.advanceTo`, forward only), so a demo market never sits closed
    overnight. `POST /ops/clock/next-open` does it on demand.
11. **Outbound failures don't halt trading:** an exception publishing market data is logged and counted
    (`publishErrors`); the journal stage still halts on any error.
12. **Observability:** ECS JSON logs in Docker (rejected orders logged with input seq, account, client order id and
    reason); Micrometer metrics at `/actuator/prometheus` (orders by kind and outcome, latency histogram, ring free
    slots, subscribers); `GET /ops/status`.
13. **Container:** Temurin 21 JRE, non-root, `JDK_JAVA_OPTIONS` for the Agrona export (`JAVA_TOOL_OPTIONS` rejects
    module flags), 768 MB limit, readiness health check via bash `/dev/tcp` (the image's `sh` is dash).

## Testing

- `OrderTracker` property test (60 random sessions of 600 commands, closes and halts included): tracker book, engine
  book and a client book rebuilt from the reported changes agree after every command.
- Recovery: a session recorded, restarted and continued replays as one unbroken session; a truncated event log is
  repaired; an event log ahead of the input journal is refused.
- `ExchangeApiTest` (real context, `mockJwt`): order lifecycle, trades between two accounts, account labels, access
  rules, validation, rate limit, WebSocket snapshot then seq+1 delta, and open orders surviving a restart.
- Planted bugs caught: a context leaking into a reused slot (the first test missed it; fixed), recovery skipping the
  repair, a modify leaving its old level, traders reaching ops endpoints, cancelling another account's order.
- `make e2e` on the live stack: feed-rebuilt books equal REST snapshots at the same seq, and the live journal (94k
  commands) replays to the recorded checksum.

## Trade-offs

- Account ids are opaque hashes; a readable account registry can come with post-trade (S16).
- The market-data lock serialises publishing and subscribing; fine for tens of subscribers, revisit for thousands.
- Ops endpoints need an `ops` token, which today only the browser flow can obtain; the ops page (S19) will use it.
