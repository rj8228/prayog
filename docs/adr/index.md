# Decisions

Architecture decision records: what was decided at each step, why, and what it costs. Each one ends with how the
decision is tested.

| ADR | Decision | Date |
|---|---|---|
| 0001 | [Spring Boot 4 and Maven](0001-spring-boot-4-and-maven.md) | 2026-10-03 |
| 0002 | [Contracts and domain types](0002-contracts-and-domain-types.md) | 2026-10-03 |
| 0003 | [Order book and limit matching](0003-order-book-and-limit-matching.md) | 2026-10-03 |
| 0004 | [Commands, market orders, cancel and modify](0004-commands-market-cancel-modify.md) | 2026-10-03 |
| 0005 | [Price bands, sessions, self-trade prevention and the kill switch](0005-bands-sessions-stp-kill-switch.md) | 2026-10-03 |
| 0006 | [Sequencer pipeline and simulated clock](0006-sequencer-pipeline-and-clock.md) | 2026-10-03 |
| 0007 | [Journal, SBE codec and deterministic replay](0007-journal-sbe-and-replay.md) | 2026-10-04 |
| 0008 | [Local infrastructure with Docker Compose](0008-local-infrastructure.md) | 2026-10-04 |
| 0009 | [Exchange service: gateway, market data and recovery](0009-exchange-service-gateway-market-data.md) | 2026-10-04 |
| 0010 | [Simulated traders, Python SDK and the web market page](0010-simulated-traders-sdk-and-web.md) | 2026-10-04 |
| 0011 | [Admin console: admin user, self-test, live simulation control, journal views](0011-admin-console.md) | 2026-10-05 |
| 0012 | [Trading workspace and browser strategies](0012-trading-workspace-and-strategies.md) | 2026-10-05 |

New ADRs go in this folder as `NNNN-short-title.md`; add a row here and an entry under **Decisions** in `mkdocs.yml`.
