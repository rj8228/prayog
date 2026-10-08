# Progress

Session definitions: docs/BUILD_PLAN.md section 10. Milestones: section 16.4.

## Summary (2026-10-08)

**Done:** every MVP session (S1-S22) and milestone (M0-M4); Step 1.5 (docs site, admin console, workspace, browser
strategies); Step 2 hardening (duplicate client order IDs, snapshots, journal archiving); the S14 realism report; the
order-latency tail investigation; allocation and GC work (ADR 0023); leaderboard names for every kind of account.
23 ADRs, full `make test` and `make e2e` green, docs site link-checked.

**Remaining to call Prayog finished**, in suggested order (each "done when" is the acceptance check):

1. **Browser check of strategy names:** start a browser strategy; done when its `user/algo-...` row shows on the
   leaderboard.
2. **Volatility clustering:** time-varying sigma or self-exciting jumps in the simulated traders; done when a longer
   realism run shows positive autocorrelation of absolute returns in `docs/realism.md`.
3. **Demo video:** 2 minutes, following `docs/demo.md`; done when linked from the README.
4. **UI tests:** component tests per panel, then Playwright journeys (Playwright needs approval); done when CI runs
   them.
5. **Releases:** tag R0 (MVP) now; R1 after items 2-4; done when GitHub releases exist with notes.
6. **Hosting:** decide where; done when a stranger can open the market without installing anything.
7. **Bot arena v1** (offline submissions, shared with Galois) as release R3.

8. **License:** the public repo has no `LICENSE` file, which legally means "all rights reserved". Pick one (MIT is
   common for showcase projects); done when `LICENSE` is in the repo and GitHub shows it.
9. **GitHub repo page:** set the homepage to https://rj8228.github.io/prayog/ and add topics (for example
   `matching-engine`, `lmax-disruptor`, `kafka`, `event-sourcing`, `java`, `spring-boot`).
10. **MVP definition of done, demo part:** BUILD_PLAN section 11 and S22 ask for a *recorded* demo. S22 is ticked for the
    script; the MVP is formally complete only when item 3 is done.
11. **Keycloak integration test:** the testing strategy (BUILD_PLAN section 12) lists Testcontainers for Keycloak;
    tokens are covered by the live stack (`make smoke`, `make e2e`) but no Testcontainers test starts Keycloak. Decide:
    add one, or record why the e2e coverage is enough (ADR).
12. **Public scaling write-up (optional):** the 1-million-user design is in the private interview notes; a short public
    version in `docs/` would show it to readers of the repo.
13. **Stale planning docs (optional):** the Claude Docs "Prayog Step 2 Handoff" (2026-10-04) and the build-plan
    artifact predate this state; mark them superseded so they don't mislead later.

Parked: JEV-model bot (needs an API key).

## M0 Setup
- [x] Tool installs (Docker Desktop, maven, uv, gh, pnpm, Python 3.12)
- [x] Git init and GitHub repo (public: github.com/rj8228/prayog)
- [x] S1 Repo skeleton and tooling (5 h)

## M1 First module
- [x] S3 Contracts and domain types (5 h) — ADR 0002
- [x] S4 Order book and limit matching (6 h) — ADR 0003

## M2 Engine complete (done 2026-10-04)
- [x] S5 Market, cancel, modify (6 h) — ADR 0004
- [x] S6 Price bands, sessions, self-trade prevention (5 h) — ADR 0005
- [x] S7 Sequencer and single-writer pipeline (6 h) — ADR 0006
- [x] S8 Journal and deterministic replay (6 h) — ADR 0007

## M3 Runnable app
- [x] S2 Local infrastructure (5 h) — ADR 0008
- [x] S10 Gateway (6 h) — ADR 0009
- [x] S11 Market data (5 h) — ADR 0009
- [x] S12 Python SDK and sample bot (5 h) — ADR 0010
- [x] S13 Simulated traders (7 h) — ADR 0010
- [x] S15 Kafka publisher (5 h) — ADR 0013
- [x] S16 Post-trade service (7 h) — ADR 0015
- [x] S17 Leaderboard (3 h) — ADR 0015
- [x] S18 Trading terminal (8 h) — ADRs 0010, 0012, 0017; server-backed blotter and P&L panels; checked in the browser 2026-10-07 (docs/media/02, 03)
- [x] S19 Blotter, P&L and ops page (6 h) — ADR 0017; a full simulated day run from the ops page 2026-10-07 (docs/media/04-ops-run-a-day.png)

## Step 1.5 (2026-10-05)
- [x] Docs site: MkDocs Material on GitHub Pages, link-checked (commits f5deacd, 02d89e5)
- [x] Admin user and console: self-test, simulation control, accounts, event replay, order journey — ADR 0011
- [x] Trading workspace: drag, resize, swap, presets, new panels, one-click, shortcuts, explain mode — ADR 0012
- [x] Strategies in the browser: six strategies with risk limits, own accounts, live P&L and logs — ADR 0012

## Step 2 additions (2026-10-07)
- [x] Duplicate client order IDs rejected per account per trading day — ADR 0014
- [x] Engine snapshots (recovery replays from the latest snapshot) — ADR 0016
- [x] Journal archiving (gzip finished segments, readers see one history) — ADR 0022, 2026-10-08
- [x] Boxing-free order index; GC settings measured — ADR 0023, 2026-10-08

## M4 Hardening layers
- [x] S9 Benchmarks (4 h) — docs/benchmarks.md
- [x] S14 Realism checks (4 h) — docs/realism.md 2026-10-08; spread and return autocorrelation realistic, fat tails weak, volatility clustering not reproduced (docs/learnings/S14-realism.md)
- [x] S20 Exchange rules as Cucumber scenarios (5 h) — ADR 0018
- [x] S21 Observability (4 h) — ADR 0019; Grafana showed a live session and Kafka lag spikes 2026-10-07 (docs/media/05-grafana.png)
- [x] S22 Docs and demo (5 h) — README, failure-modes.md, demo.md (recording: follow docs/demo.md)
- [x] Multi-arch images — ADR 0021 (`make images`, CI job)
- [x] Phase 2 items pulled into scope (agent rate-limit tier) — ADR 0020

## Backlog (from the handoff, focus plan and ideas docs, 2026-10-07)

MVP close-out
- [x] S14 report: run the realism report on the live stack, commit docs/realism.md and docs/learnings/S14
- [ ] Volatility clustering in the simulator (time-varying sigma or self-exciting jumps), then a longer realism run
- [x] Learnings for S18 and S19 (ops page, blotter, server P&L), linked from docs/learnings/README.md
- [ ] Record the demo video (docs/demo.md script; raw capture in docs/media/raw) and link it from the README
- [x] Full `make test` and `make e2e` on the repaired stack (2026-10-08)
- [x] Update the Step 2 handoff doc — replaced by a Step 3 handoff in the private interview notes, 2026-10-08
- [x] Investigate new-order p99 in Grafana: Serial GC pauses slowed about 6x (likely host memory pressure); steady-state p99 14 ms; ZGC worse in a 768 MiB container (docs/benchmarks.md, 2026-10-08)

Hardening
- [ ] UI tests: component tests per panel and Playwright journeys (Playwright needs approval) — deferred 2026-10-08
- [x] Journal segment archiving — ADR 0022
- [x] Leaderboard: simulated traders show names (confirmed 2026-10-08); browser strategy accounts did not, fixed in 3e107ba

Showcase (focus plan definition of done)
- [ ] Host it so a stranger can use it, with a 2-minute demo video (hosting not decided)
- [x] JMH baseline, then GC tuning and lock-free changes, each with before and after numbers — ADR 0023, docs/benchmarks.md 2026-10-08 (allocation -12 to -21%; GC settings change pauses, not the laptop's tail; matching already lock-free)
- [x] Written design for 1 million users (shard by market, hot standby replaying the journal, market data fan-out with conflation and backpressure) — in the private interview notes, 2026-10-08
- [ ] JEV-model bot (TypeSafe AI's Jev decision API) against a random baseline — skipped 2026-10-08, needs an API key
- [ ] Releases R0 to R3 tagged, ending with bot arena v1 (offline submissions, shared with Galois)
- [x] Interview companion entries for Prayog: pitch, numbers, failures, decisions — in the private interview notes, 2026-10-08

