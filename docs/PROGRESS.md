# Progress

Session definitions: docs/BUILD_PLAN.md section 10. Milestones: section 16.4.

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
- [ ] S16 Post-trade service (7 h)
- [ ] S17 Leaderboard (3 h)
- [ ] S18 Trading terminal (8 h) — first slice done (ADR 0010); Step 1.5 added the draggable workspace, watchlist, depth chart, positions & P&L, one-click trading, shortcuts and explain mode (ADR 0012); remaining: server-backed blotter and P&L with S16/S19
- [ ] S19 Blotter, P&L and ops page (6 h)

## Step 1.5 (2026-10-05)
- [x] Docs site: MkDocs Material on GitHub Pages, link-checked (commits f5deacd, 02d89e5)
- [x] Admin user and console: self-test, simulation control, accounts, event replay, order journey — ADR 0011
- [x] Trading workspace: drag, resize, swap, presets, new panels, one-click, shortcuts, explain mode — ADR 0012
- [x] Strategies in the browser: six strategies with risk limits, own accounts, live P&L and logs — ADR 0012

## Step 2 additions (2026-10-07)
- [x] Duplicate client order IDs rejected per account per trading day — ADR 0014
- [ ] Engine snapshots (recovery replays from the latest snapshot)

## M4 Hardening layers
- [ ] S9 Benchmarks (4 h)
- [ ] S14 Realism checks (4 h)
- [ ] S20 Exchange rules as Cucumber scenarios (5 h)
- [ ] S21 Observability (4 h)
- [ ] S22 Docs and demo (5 h)
- [ ] Multi-arch images
- [ ] Phase 2 items pulled into scope (agent rate-limit tier)
