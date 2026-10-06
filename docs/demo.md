# A 3-minute demo

A script for showing Prayog live, for example in an interview. Before you start: `make up PROFILES="infra app obs"`,
one browser window with four tabs (the market at http://app.prayog.localhost, `/ops` signed in as `ops1` or
`admin1`, Grafana at http://grafana.prayog.localhost, the docs site), and one terminal.

| Time | Show | Say |
|---|---|---|
| 0:00 | The market tab, not signed in: ticker bar, order book, chart, trade tape | "This is a simulated stock exchange. Every order here comes from simulated traders — a market maker, noise and momentum traders — through the same public API a bot would use." |
| 0:20 | Sign in as `trader1`; one-click buy from the order book; the fill toast; Positions shows official P&L and charges | "My order went through a single-threaded matching engine on a Disruptor ring, was journaled with an fsync, and only then answered. Positions are the official numbers from the post-trade ledger." |
| 0:50 | Workspace preset **Review**: Blotter and Leaderboard | "Post-trade consumes every exchange event from Kafka into PostgreSQL: average-cost P&L in exact paise, a Redis leaderboard." |
| 1:10 | Ops tab: **Run a day at 300x**; the progress bar moves; Grafana: commands per second rise, ring stays near zero | "A whole trading day in about 75 seconds. Grafana shows latency, throughput, queue depth and lag." |
| 1:40 | Terminal: `docker compose -f deploy/compose/compose.yaml stop kafka`; Grafana: Kafka lag climbs, trading continues; start it again, lag drains | "Kafka is off the order path. The publisher tails the fsynced journal, so an outage only delays the stream — `make e2e` checks every event id arrives." |
| 2:10 | Ops page: the zero-sum check is "yes" | "The ledger is checked as a whole: every share bought was sold by someone, so net quantity and P&L before charges sum to exactly zero." |
| 2:25 | Docs site: an ADR and `benchmarks.md` | "Every decision has an ADR, every number a benchmark with its command and machine: about 100 ns per command in the engine; end to end the fsync dominates, and GC logs explained the tail." |
| 2:45 | Terminal: last lines of `make e2e` (MATCH, snapshots verified) | "The live journal replays to a byte-identical event log, and restarts start from verified snapshots. That's the backbone: same inputs, same outputs." |

Recording: QuickTime (File → New Screen Recording) or OBS at 1080p; keep the terminal font at 16 pt or larger.
