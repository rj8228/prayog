# CLAUDE.md

Prayog is a simulated stock exchange and bot arena: simulated traders create the market; people trade by hand or with bots. Full plan: docs/BUILD_PLAN.md. Progress: docs/PROGRESS.md.

## Rules
- No employer code, data, names or designs. Public knowledge and textbook algorithms only.
- Tests first for core logic. A change is done only when its tests pass.
- Measure, don't guess: every performance number comes from a benchmark recorded in docs/benchmarks.md (command, machine, date).
- Ask before adding any dependency not listed in docs/BUILD_PLAN.md section 8.
- No secrets in git. Use .env files (git-ignored) and .env.example.
- Conventional commits. Commit after every green test run.

## Stack
- Java 21, Spring Boot 4, Maven (multi-module, Maven Wrapper), jOOQ (no JPA, no MyBatis), Flyway, LMAX Disruptor, JUnit 5, jqwik, Testcontainers, JMH.
- Python 3.12, uv, Ruff, pytest (agents and SDK).
- React, TypeScript, Vite, Redux Toolkit, pnpm (web).
- Kafka (KRaft), PostgreSQL 16, Redis 7, Keycloak, Traefik, Docker Compose.

## Engine invariants (never break)
- Prices and quantities are integers (price in paise as long). Never use floating point for money.
- One writer thread per shard. No locks in matching logic.
- Time comes from the simulated clock passed in as input, never from the system clock.
- Same seed and inputs must produce a byte-identical event log.
- The order path never calls the network or a database.

## Token hygiene
- Read only the files named in the task, plus what you need to find. Never read target/, generated/, node_modules/ or dist/.
- Run only the tests for the module you changed; run the full suite before the final commit of a session.
- When a command fails, show only the relevant error lines.
- Keep answers short; no recap of unchanged code.

## Session workflow
1. Restate the session goal and acceptance criteria from docs/BUILD_PLAN.md section 10.
2. Propose a plan of at most 10 steps. Wait for approval.
3. Implement in small steps with tests.
4. Run tests, commit.
5. Ask me 5 explain-back questions about what was built (design choices, failure modes, trade-offs). Do not continue until I answer.
6. Tick the session in docs/PROGRESS.md, note decisions in docs/adr/ when a choice was made, and add the session's concepts and explain-back answers to docs/learnings/ (one file per session, linked from its README).
