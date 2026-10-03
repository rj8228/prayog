# Learnings

What I learned building Prayog, session by session: the functional side (how exchanges work) and the technical side (how to build one). Each session file has:

- **What was built**: one paragraph, with links to the commit and the ADRs.
- **Concepts**: each one has a checkbox. I tick it once I can explain it without notes.
- **In an interview**: a one- or two-sentence answer I could give out loud.

The longer background reading lives in [`docs/overview/functional.html`](../overview/functional.html) and [`docs/overview/technical.html`](../overview/technical.html). The decisions themselves live in [`docs/adr/`](../adr/).

## Index

| Session | File | Main topics |
|---|---|---|
| S1 Repo skeleton | [S01-repo-skeleton.md](S01-repo-skeleton.md) | Module boundaries, Maven parent POMs, wrappers, lock files, pinning CI actions |
| S3–S4 Contracts and order book | [S03-S04-contracts-and-order-book.md](S03-S04-contracts-and-order-book.md) | JSON Schema contracts, integer money, price-time priority, order book data structures, determinism, property testing |
| S5 Market, cancel, modify | [S05-market-cancel-modify.md](S05-market-cancel-modify.md) | Commands and event sourcing, modify semantics, queue priority, information leaks, test coverage |
| S6 Bands, sessions, STP | [S06-bands-sessions-stp.md](S06-bands-sessions-stp.md) | Price bands, session states, DAY expiry, self-trade prevention, kill switch |
| S7 Sequencer pipeline | [S07-sequencer-pipeline.md](S07-sequencer-pipeline.md) | Single writer, ring buffers, back-pressure, wait strategies, simulated clock, testing concurrency |
| Industry context | [industry-context.md](industry-context.md) | Exchange vs trading firm, HFT components, what production engines do differently |

## Themes so far

- **Determinism is the backbone.** Integer money, time as an input, single-threaded matching and no hash-map iteration all serve one goal: the same inputs always produce the same output. That makes replay, crash recovery and later hot standbys possible.
- **Fairness rules are anti-gaming rules.** Price-time priority, the modify priority rules and opaque errors all exist to stop one participant from exploiting another.
- **Tests must be checked too.** Planting bugs on purpose (mutation testing) showed that one random test generator was missing a whole code path.
