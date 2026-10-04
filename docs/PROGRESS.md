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
- [ ] S15 Kafka publisher (5 h)
- [ ] S16 Post-trade service (7 h)
- [ ] S17 Leaderboard (3 h)
- [ ] S18 Trading terminal (8 h) — first slice done (market view, sign-in, order ticket, my orders and fills; ADR 0010); full terminal remains
- [ ] S19 Blotter, P&L and ops page (6 h)

## M4 Hardening layers
- [ ] S9 Benchmarks (4 h)
- [ ] S14 Realism checks (4 h)
- [ ] S20 Exchange rules as Cucumber scenarios (5 h)
- [ ] S21 Observability (4 h)
- [ ] S22 Docs and demo (5 h)
- [ ] Multi-arch images
- [ ] Phase 2 items pulled into scope (agent rate-limit tier)
