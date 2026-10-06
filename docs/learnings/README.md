# Learnings

What I learned building Prayog, session by session: the functional side (how exchanges work) and the technical side (how to build one). Each session file has:

- **What was built**: one paragraph, with links to the commit and the ADRs.
- **Concepts**: each one has a checkbox. I tick it once I can explain it without notes.
- **In an interview**: a one- or two-sentence answer I could give out loud.

The longer background reading lives in [`docs/overview/functional.html`](../overview/functional.html), [`docs/overview/technical.html`](../overview/technical.html) and [`docs/overview/ring-buffer.html`](../overview/ring-buffer.html) (with an interactive simulator). The decisions themselves live in [`docs/adr/`](../adr/index.md).

## Topic pages

Interactive explainers in [`docs/overview/`](../overview/index.md), each with a business example, the mechanics, a try-it panel and how Prayog uses it. Open with `open docs/overview/<name>.html`.

| Area | Page | Covers |
|---|---|---|
| Trading infrastructure | [ring-buffer.html](../overview/ring-buffer.html) | LMAX Disruptor, single writer, back-pressure (S7) |
| Trading infrastructure | [sbe.html](../overview/sbe.html) | Simple Binary Encoding: layout, flyweights, schema evolution, live encoder (S8) |
| Trading infrastructure | [fix-protocol.html](../overview/fix-protocol.html) | FIX tag=value, order lifecycle, ExecutionReport, session layer and resend, message builder and parser |
| Trading infrastructure | [journaling.html](../overview/journaling.html) | fsync, write-ahead journal, group commit, torn writes, CRC32C, segments, replay, crash simulator (S8) |
| Platform | [kafka.html](../overview/kafka.html) | Kafka basics: log, topics, partitions, offsets, consumer groups, delivery guarantees, KRaft, listeners (S2) |
| Platform | [oauth-oidc.html](../overview/oauth-oidc.html) | OAuth2 and OpenID Connect with Keycloak: clients, PKCE, client credentials, JWT claims and validation (S2) |
| Platform | [compose-traefik.html](../overview/compose-traefik.html) | Containers, Docker Compose, health checks, volumes, reverse proxying with Traefik (S2) |
| Trading infrastructure | [market-data-feeds.html](../overview/market-data-feeds.html) | Level 1/2/3, snapshot plus deltas, sequence gaps, slow consumers, multicast feeds; drop-a-message simulator (S11) |
| Trading infrastructure | [market-making.html](../overview/market-making.html) | Spread, inventory skew, adverse selection, self-trade and reconciliation traps; market-maker simulator (S13) |
| Core Java | [java-memory-model.html](../overview/java-memory-model.html) | Happens-before, volatile, release/acquire, CAS, cache lines, false sharing, litmus test |
| Core Java | [java-off-heap.html](../overview/java-off-heap.html) | Heap vs direct vs mapped buffers, Unsafe, VarHandle, FFM API, Agrona, `--add-exports`, endianness |
| Core Java | [java-gc-jit.html](../overview/java-gc-jit.html) | Allocation, collectors (G1, ZGC), garbage-free code, JIT tiers, warm-up, safepoints, JMH, coordinated omission |
| Core Java | [modern-java.html](../overview/modern-java.html) | Records, sealed types, pattern-matching switch and exhaustiveness, virtual threads |

## Index

| Session | File | Main topics |
|---|---|---|
| S1 Repo skeleton | [S01-repo-skeleton.md](S01-repo-skeleton.md) | Module boundaries, Maven parent POMs, wrappers, lock files, pinning CI actions |
| S2 Local infrastructure | [S02-local-infrastructure.md](S02-local-infrastructure.md) | Compose, health checks, Kafka KRaft and listeners, OAuth2/OIDC and PKCE, reverse proxy, secrets |
| S3–S4 Contracts and order book | [S03-S04-contracts-and-order-book.md](S03-S04-contracts-and-order-book.md) | JSON Schema contracts, integer money, price-time priority, order book data structures, determinism, property testing |
| S5 Market, cancel, modify | [S05-market-cancel-modify.md](S05-market-cancel-modify.md) | Commands and event sourcing, modify semantics, queue priority, information leaks, test coverage |
| S6 Bands, sessions, STP | [S06-bands-sessions-stp.md](S06-bands-sessions-stp.md) | Price bands, session states, DAY expiry, self-trade prevention, kill switch |
| S7 Sequencer pipeline | [S07-sequencer-pipeline.md](S07-sequencer-pipeline.md) | Single writer, ring buffers, back-pressure, wait strategies, simulated clock, testing concurrency |
| S8 Journal and replay | [S08-journal-and-replay.md](S08-journal-and-replay.md) | Write-ahead journal, fsync and group commit, torn writes and CRC, SBE, deterministic replay checksums |
| S10–S13 Live market | [S10-S13-live-market.md](S10-S13-live-market.md) | Gateway, recovery, derived market data, feeds and gaps, account ids, market making, reconciliation, e2e correctness |
| Step 1.5 Admin, workspace, strategies | [step-1.5-admin-workspace-strategies.md](step-1.5-admin-workspace-strategies.md) | Admin role and self-test, online replay, live simulation control, grid layouts, average-cost P&L, pure strategies, pre-trade risk |
| S15 Kafka publisher | [S15-kafka-publisher.md](S15-kafka-publisher.md) | Topics, partitions and offsets, delivery guarantees, idempotent producers, contiguous acked checkpoint, journal as outbox, chaos tests |
| Duplicate client order IDs | [step-2-duplicate-client-order-ids.md](step-2-duplicate-client-order-ids.md) | Idempotency keys, retries after timeouts, bounded state, rule changes vs replay, test oracles |
| S16–S17 Post-trade and leaderboard | [S16-S17-post-trade-and-leaderboard.md](S16-S17-post-trade-and-leaderboard.md) | Idempotent consumers, exactly-once effects, average cost in integers, zero-sum reconciliation, sorted sets, rebuildable views |
| Industry context | [industry-context.md](industry-context.md) | Exchange vs trading firm, HFT components, what production engines do differently |

## Themes so far

- **Determinism is the backbone.** Integer money, time as an input, single-threaded matching and no hash-map iteration all serve one goal: the same inputs always produce the same output. That makes replay, crash recovery and later hot standbys possible.
- **Fairness rules are anti-gaming rules.** Price-time priority, the modify priority rules and opaque errors all exist to stop one participant from exploiting another.
- **Tests must be checked too.** Planting bugs on purpose (mutation testing) showed that one random test generator was missing a whole code path.
