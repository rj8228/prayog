# S22 Docs and demo

**Built:** a README with the architecture as a Mermaid diagram, the guarantees and how to run everything;
[failure-modes.md](../failure-modes.md) (what breaks, how it is detected, how it recovers, which test proves it); and
a [3-minute demo script](../demo.md).

## Concepts

- [ ] **A failure-mode table is a test index.** Every row names the test or drill that proves the recovery; a row
  without one is a gap.
- [ ] **Docs claims must be checked.** Writing the table showed that the exchange had no restart policy, so "Compose
  restarts it" was not yet true. It is now.
- [ ] **Diagrams as code.** Mermaid in the README renders on GitHub and changes with the code in the same commit.
- [ ] **A demo is a story.** Lead with what the audience sees, then one proof of each guarantee: outage, zero-sum,
  replay.

## Explain-back: questions and model answers

### 1. Why does every row of the failure-mode table name a test?

**Answer:** A recovery that has never been exercised is a hope. Naming the test (or drill) makes the table an index of
evidence. Writing it is also a review: rows without a test are where to add one next. For example, the PostgreSQL
outage row points only at configuration, a candidate for a Testcontainers test that pauses the database.

### 2. What did writing the docs find that the tests had not?

**Answer:** The exchange and post-trade had no `restart:` policy, so a crash would have left them down until
someone ran `make up`. Recovery itself was well tested, but nothing triggered it automatically. Both services now
have `restart: unless-stopped`; an explicit `docker compose stop` (as `make e2e` does) is still respected.

### 3. Why is the architecture diagram in the README written in Mermaid?

**Answer:** It is text, so it is reviewed in pull requests, it diffs, and it changes in the same commit as the code
it describes. GitHub and the docs site render it, and there is no image file to go stale.

### 4. What is the order of the demo, and why?

**Answer:** It goes from the user's view inwards:
1. a live market;
2. a trade with official P&L;
3. a whole day at speed, with the dashboards;
4. a Kafka outage the market does not notice;
5. the zero-sum check;
6. the replay check.

Each claim made in the first minute is proven before the end. Breaking something on purpose and showing nothing is
lost is more convincing than any slide.

### 5. Which guarantees would you choose if you had only 30 seconds?

**Answer:**
- "Same inputs, same outputs: the live journal replays to a byte-identical event log, and restarts start from
  snapshots that are verified against a full replay."
- "Kafka is off the order path: kill it and trading continues, and every event still arrives."

Those two show determinism and decoupling, the two ideas everything else rests on.

## In an interview

"The README draws the system, and failure-modes.md lists every failure with its detection, recovery and the test that
proves it. Writing it found a real gap (no restart policy), which tells you why the table is worth keeping."
