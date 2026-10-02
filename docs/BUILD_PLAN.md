# Prayog: Build Plan for Claude Code (MVP)

Version 1.2 · 3 Oct 2026 (Spring Boot 4; confirmed defaults and milestones in section 16) · Owner: Raj Joshi

## 0. How to use this file

Before any code is written, Claude Code must list every default it will use (section 8 and anything implied elsewhere) and wait for Raj to confirm. Build tool is Maven, not Gradle.

1. Create an empty GitHub repo named `prayog` and clone it.
2. Save this file as `docs/BUILD_PLAN.md`.
3. Open the repo in Claude Code and send this first prompt:

   > Read docs/BUILD_PLAN.md. Create CLAUDE.md in the repo root using section 1 exactly. Create docs/PROGRESS.md with the session list from section 10, all unchecked. Then show me your plan for Session S1. Do not write any other code yet.

4. For every later session, use the prompt template in section 9.

---

## 1. CLAUDE.md (copy this block into the repo root as-is)

```markdown
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
6. Tick the session in docs/PROGRESS.md and note decisions in docs/adr/ when a choice was made.
```

---

## 2. Product summary

- **Prayog** is a realistic simulated exchange with fictitious NSE-style stocks. A hidden fair-value path per stock drives simulated traders; the market emerges from their orders.
- **MVP goal:** a live simulated market you can trade by hand or with a Python bot, with exact replay and P&L, running on one machine with `make up`.
- **Why this project:** flagship CV project for trading-technology and banking roles; it needs no external market data.
- Design docs (for humans, written in Claude Docs): Prayog BRD v0.1 and Prayog Technical Discovery v0.1.

## 3. MVP scope

**In the MVP**

1. Order book and matching for 3-5 fictitious stocks: limit, market, cancel, modify; price-time priority; price bands; continuous trading only.
2. Input journal and deterministic replay with a checksum test.
3. Gateway: REST and WebSocket, bot API keys, rate limits, kill switch.
4. Market data: top of book, depth, trades, with sequence numbers.
5. Simulated traders: market maker, noise, momentum; seeded fair value; scenarios `calm` and `volatile`.
6. After the trade: Kafka to a post-trade service that keeps positions and P&L with a simple charge model (PostgreSQL via jOOQ).
7. Web: trading terminal (order entry, depth ladder, chart, blotter, P&L) and a minimal ops page.
8. Python bot SDK with one sample bot and a quick-start guide.
9. Leaderboard by net P&L (Redis).
10. Keycloak sign-in, metrics, a JMH benchmark, CI with order-book and replay tests.

**Not in the MVP (do not build yet):** opening auction, IOC and stop-loss orders, surveillance and Elasticsearch, contract-note PDFs, settlement, replay viewer UI, risk-adjusted scoring, seasons, hosted bots, FIX, ETF baskets, options, AI assistant, cloud deployment.

## 4. Architecture (MVP)

| Deployable | Tech | Role |
|---|---|---|
| `services/exchange` | Java 21, Spring Boot 4, Disruptor | Gateway + sequencer + matching engine + market-data publisher in one process |
| `services/post-trade` | Java 21, Spring Boot, jOOQ, Flyway | Kafka consumer: positions, P&L, charges; leaderboard updates |
| `services/agents` | Python, asyncio | Simulated traders using the public API (no back door) |
| `apps/web` | React, TypeScript, Vite, Redux Toolkit | Terminal and ops page |
| `sdk/python` | Python | Bot client library and sample bot |
| Infra | Kafka (KRaft), PostgreSQL, Redis, Keycloak, Traefik | Local via Docker Compose |

**Order path:** gateway validates and rate-limits → Disruptor ring → single matching thread per shard (pre-trade checks, matching) → events to: journal writer, market-data publisher, Kafka publisher.

**After the trade:** the Kafka publisher runs on its own thread, off the order path. The journal is the source of truth; if Kafka is down, trading continues and the publisher catches up from the journal. Post-trade consumers are idempotent (unique event IDs).

**Determinism:** every input gets a sequence number; the simulated clock is an input; agents use seeded random numbers; replaying the journal must reproduce a byte-identical event log.

## 5. Domain basics

- **Instrument:** symbol, tick size (paise), lot size (1 for MVP), daily price band (percent of reference price), reference price.
- **Order:** order ID (server-assigned, monotonic), client order ID, account ID, symbol, side, type (LIMIT, MARKET), price (paise, long), quantity (long), sim timestamp.
- **Matching rules:**
  - Price, then time priority.
  - Market orders take liquidity up to the price band; any unfilled remainder is cancelled.
  - Modify: reducing quantity keeps priority; increasing quantity or changing price loses priority.
  - Self-trade prevention: cancel the incoming order.
  - Orders priced outside the band are rejected.
- **Events:** OrderAccepted, OrderRejected (with reason), OrderCancelled, OrderModified, Trade (aggressor side, both order IDs), BookUpdate, SessionStateChanged. Each event has a sequence number and sim time.
- **Charges (simple MVP model):** configurable per-trade brokerage plus percentage fees; detailed Indian charge model comes later.

## 6. Repository layout

```
prayog/
  services/exchange/      services/post-trade/      services/agents/
  apps/web/               sdk/python/               contracts/
  deploy/compose/         docs/ (BUILD_PLAN.md, PROGRESS.md, adr/, benchmarks.md, failure-modes.md)
  Makefile  CLAUDE.md  README.md  .github/workflows/
```

`contracts/` holds the REST and WebSocket message schemas and the Kafka event schemas (JSON Schema for the MVP).

## 7. Container contract

1. One process per container; one Dockerfile per deployable (multi-stage).
2. Configuration only through environment variables; no hostnames in images.
3. Health and readiness endpoints; clean shutdown on stop signal.
4. JSON logs to standard output.
5. Stateless except declared volumes (exchange journal, PostgreSQL, Kafka).
6. Images built for ARM and x86, non-root user, pinned base images, tagged by Git commit.
7. Flyway migrations run as a one-off job before services start.
8. JVM memory set as a percentage of the container limit.
9. Exactly one exchange instance may run at a time (single writer).

**Local addresses (Traefik):** `app.prayog.localhost` (web), `api.prayog.localhost` (gateway), `auth.prayog.localhost` (Keycloak), `grafana.prayog.localhost`.

## 8. Decisions

**Taken:** Java 21; Spring Boot 4 (changed from 3 on 3 Oct 2026); Maven (multi-module parent POM, Maven Wrapper); jOOQ; Flyway; LMAX Disruptor; Kafka for everything after the trade; PostgreSQL; Redis; Keycloak; Traefik subdomains; monorepo; synthetic market; bring-your-own bots before hosted bots.

**Defaults for open decisions (change only if Raj says so):**

| Decision | Default for MVP | Revisit |
|---|---|---|
| Journal | Simple append-only file journal behind a `Journal` interface | Chronicle Queue if performance demands |
| Gateway transport | Spring WebFlux WebSockets | Netty directly if latency demands |
| Kafka message format | JSON validated by JSON Schema in `contracts/` | Avro with a schema registry later |
| Product after equities | Not decided | After MVP |

**Defaults to confirm before Session S1:** Java and Spring Boot versions; Maven layout (parent POM, module names, Maven Wrapper); jOOQ and Flyway setup; Disruptor configuration (ring size, wait strategy); journal format; gateway transport; message format and schema location; Keycloak realm and clients; Traefik subdomains and ports; Python tooling (uv, Ruff, pytest); web tooling (pnpm, Vite, Redux Toolkit, charting library); folder layout; Makefile targets; CI steps.

**Approved dependency list (ask before adding others):** Spring Boot starters (web, webflux, actuator, security, oauth2-resource-server), jOOQ, Flyway, PostgreSQL driver, spring-kafka, Lettuce (Redis), LMAX Disruptor, Agrona (optional), Micrometer + Prometheus registry, JUnit 5, jqwik, Testcontainers, JMH, HdrHistogram, Cucumber-JVM; Python: websockets or httpx, pydantic, numpy, pytest, ruff; Web: react, redux toolkit, react-router, a charting library (ask first).

## 9. Session prompt template

```
Session S<n>: <title> (docs/BUILD_PLAN.md section 10).
Restate the goal and acceptance criteria, then propose a plan of at most 10 steps and wait.
Only touch: <folders>.
Stop when the acceptance criteria pass; commit; then ask me the 5 explain-back questions.
```

## 10. Sessions (MVP, about 110-130 hours)

Estimates are rough. One session fits one 5-hour usage window.

### Phase A: Foundation

- [ ] **S1 Repo skeleton and tooling (5 h)** — Maven multi-module (parent POM, Maven Wrapper `./mvnw`) for Java services, uv project for Python, pnpm workspace for web, Makefile (`up`, `down`, `test`, `fmt`), GitHub Actions running build and tests, `.env.example`. *Done when:* `make test` passes on an empty skeleton and CI is green.
- [ ] **S2 Local infrastructure (5 h)** — Compose with profiles: PostgreSQL, Kafka (KRaft), Redis, Keycloak (realm import with a web client and a bot client), Traefik with the subdomains. Health checks and start order. *Done when:* `make up` brings everything healthy and the subdomains respond.
- [ ] **S3 Contracts and domain types (5 h)** — JSON Schemas for orders, events and market data; Java domain types (Price and Quantity as longs, IDs, enums). *Done when:* schemas validate sample messages in tests.

### Phase B: Matching engine

- [ ] **S4 Order book and limit matching (6 h)** — Book per symbol, price levels, FIFO queues, limit orders, trades. Property tests: book never crossed; quantity conserved. *Done when:* unit and property tests pass.
- [ ] **S5 Market, cancel, modify (6 h)** — Rules from section 5, including priority on modify. *Done when:* tests cover every rule.
- [ ] **S6 Price bands, sessions, self-trade prevention (5 h)** — Band rejection, session states (closed, open, halted), cancel-incoming STP. *Done when:* tests pass.
- [ ] **S7 Sequencer and single-writer pipeline (6 h)** — Disruptor ring, sequence numbers, sim clock as input, event fan-out handlers. *Done when:* concurrent submit test shows deterministic ordering.
- [ ] **S8 Journal and deterministic replay (6 h)** — Append-only journal behind an interface; replay tool; checksum test in CI. *Done when:* replaying a recorded session gives an identical event-log checksum.
- [ ] **S9 Benchmarks (4 h)** — JMH for book operations (as a separate Maven module); end-to-end latency harness with HdrHistogram. *Done when:* numbers recorded in docs/benchmarks.md with method and machine.

### Phase C: Gateway and market data

- [ ] **S10 Gateway (6 h)** — REST and WebSocket order entry; Keycloak JWT for web users and client-credentials for bots; per-account rate limits; kill switch. *Done when:* integration tests place, modify and cancel orders through the API.
- [ ] **S11 Market data (5 h)** — Top of book, depth and trades over WebSocket with sequence numbers; snapshot then deltas. *Done when:* a test client rebuilds the book exactly from the feed.

### Phase D: Market life

- [ ] **S12 Python SDK and sample bot (5 h)** — Connect, authenticate, subscribe, place and cancel; a simple sample bot; quick-start README. *Done when:* the sample bot trades against the local exchange.
- [ ] **S13 Simulated traders (7 h)** — Fair-value process (mean-reverting with jumps), market maker, noise and momentum traders; scenarios `calm` and `volatile`; seeded. *Done when:* a 30-minute simulated session runs with continuous two-sided quotes.
- [ ] **S14 Realism checks (4 h)** — Script reporting return distribution, volatility clustering and spread statistics per scenario. *Done when:* report saved for both scenarios.

### Phase E: After the trade

- [ ] **S15 Kafka publisher (5 h)** — Publisher thread off the order path; catch-up from the journal after Kafka downtime. *Done when:* killing Kafka mid-session loses no events after restart.
- [ ] **S16 Post-trade service (7 h)** — Flyway schema, jOOQ code generation with the jooq-codegen-maven plugin (generate from a Flyway-migrated PostgreSQL started by Testcontainers), idempotent consumer, positions, realised and unrealised P&L, simple charges. *Done when:* P&L reconciles exactly with the trade log in a test.
- [ ] **S17 Leaderboard (3 h)** — Redis sorted set by net P&L; REST endpoint. *Done when:* leaderboard updates during a live session.

### Phase F: Web

- [ ] **S18 Trading terminal (8 h)** — Sign-in, order entry, depth ladder, trade tape, price chart. *Done when:* manual trading works end to end in the browser.
- [ ] **S19 Blotter, P&L and ops page (6 h)** — Orders and trades blotter, P&L panel, leaderboard view; ops page to start, stop and switch scenarios. *Done when:* a full simulated day can be run from the ops page.

### Phase G: Hardening and showcase

- [ ] **S20 Exchange rules as Cucumber scenarios (5 h)** — Key matching rules written as Given/When/Then and run in CI. *Done when:* scenarios pass in CI.
- [ ] **S21 Observability (4 h)** — Micrometer metrics, Prometheus and Grafana dashboards: latency, throughput, queue depth, consumer lag. *Done when:* dashboards show a live session.
- [ ] **S22 Docs and demo (5 h)** — README with architecture diagram (Mermaid), docs/failure-modes.md, ADRs, a 3-minute demo script, recorded demo. *Done when:* a newcomer can run `make up` and follow the README.

## 11. Definition of done

**Per session:** acceptance criteria met; tests green; committed; explain-back answered; PROGRESS.md ticked; ADR written if a decision was made.

**MVP:** `make up` starts everything; a simulated day runs; a person and the sample bot can trade; replay checksum passes in CI; P&L reconciles; benchmarks published; README and demo done.

## 12. Testing strategy

- Unit and property-based tests (jqwik) for the order book.
- Cucumber scenarios for exchange rules.
- Integration tests with Testcontainers (PostgreSQL, Kafka, Redis, Keycloak).
- Replay determinism test in CI.
- Chaos checks: stop Kafka or the post-trade service mid-session and verify recovery.

## 13. Interview notes to keep as you go

- `docs/adr/` — one short record per decision: context, options, choice, trade-off.
- `docs/benchmarks.md` — every measured number with command, machine and date.
- `docs/failure-modes.md` — what breaks, how it is detected, how it recovers.

## 14. Usage-window hygiene (5-hour limit)

- Start a session only when ready for a full block; one session per window.
- `/clear` between sessions; `/compact` in long ones; `/usage` to check headroom.
- Name exact files and folders in prompts; keep CLAUDE.md short.
- Prefer the default model; use the most capable model only for hard design questions.
- Keep heavy chat in claude.ai to a minimum on build days: chat and Claude Code share one usage pool.

## 15. After the MVP (do not build now)

v1.1: opening auction, IOC and stop-loss, fuller ops console, replay viewer. Phase 2: contract notes, settlement, surveillance with Elasticsearch, risk-adjusted leaderboards, backtester, outside bots. Phase 3: RJ's Trading Challenge seasons, hosted sandbox bots, ETF basket and options, FIX. Phase 4: AI assistant. Cloud deployment (ECS or Kubernetes) when hosting is decided.

---

## 16. Confirmed decisions (3 Oct 2026)

Confirmed by Raj before M0. These override earlier sections where they differ.

### 16.1 Technical defaults

| # | Item | Decision |
|---|---|---|
| 1 | Java | 21 LTS (Temurin in CI and images) |
| 2 | Spring Boot | 4.x, latest patch (Spring Framework 7, Jakarta EE 11, Jackson 3 `tools.jackson`, modular starters such as `spring-boot-starter-flyway`, `-jooq`, `-kafka`). Exact versions of jOOQ, Flyway, Testcontainers, jqwik and JUnit are verified for compatibility in M0 and recorded here |
| 3 | Build | Maven 3.9 + Maven Wrapper (`./mvnw`). The root `pom.xml` is both parent and aggregator, with `dependencyManagement` and `pluginManagement` |
| 4 | Maven modules | `contracts` (JSON Schemas + Java event records), `services/exchange/exchange-core` (plain Java engine, no Spring), `services/exchange/exchange-app` (Spring Boot), `services/exchange/exchange-bench` (JMH), `services/post-trade` |
| 5 | Java package / groupId | `dev.prayog` |
| 6 | Java formatting | Spotless + palantir-java-format (`make fmt`; checked in CI) |
| 7 | Logging | Spring Boot structured logging (JSON to stdout) |
| 8 | Java tests | JUnit + jqwik + AssertJ; unit tests via Surefire run without Docker; integration tests (`*IT`) via Failsafe use Testcontainers |
| 9 | Flyway | Migrations in `services/post-trade/src/main/resources/db/migration`; Spring auto-migrate off; one-off `flyway/flyway` job in Compose |
| 10 | jOOQ | 3.20.x OSS; code generated at build time by `testcontainers-jooq-codegen-maven-plugin` from a Flyway-migrated Testcontainers PostgreSQL; generated code not committed (build needs Docker) |
| 11 | Disruptor | 4.x; ring size 65,536; `ProducerType.MULTI`; wait strategy set by env var (default `BLOCKING`; `BUSY_SPIN`/`YIELDING` for benchmarks) |
| 12 | Shards | 1 shard (all symbols on one matching thread) for the MVP |
| 13 | Sequencing | Input sequence and event sequence assigned on the matching thread |
| 14 | Journal | Two append-only files. **Input journal**: every command received (used for replay). **Event log**: every event the engine produced (used for the checksum and Kafka catch-up). Custom binary records `[len][seq][type][payload][crc32c]`, segment files under `/data/journal`, fsync at end of each Disruptor batch. Determinism checksum: SHA-256 over the event-log bytes |
| 15 | Determinism scope | Replaying the input journal reproduces a byte-identical event log. Re-running agents with the same seed does **not** reproduce a session, because their orders arrive over the network with nondeterministic timing |
| 16 | Sim clock | A clock thread off the order path reads wall time every 100 ms and publishes `ClockTick(simTime)` commands, which are journaled. `simTime = sessionStart + wallElapsed × multiplier`. **Configurable**: multiplier, day open and day close come from env vars (defaults `1`, `09:15`, `15:30`) and the multiplier can be changed at runtime from the ops page. The clock thread changes only the tick rate, so replay stays deterministic. S13's "30-minute session" means 30 wall minutes at 1× |
| 17 | Gateway | Spring WebFlux for REST and WebSocket (no MVC in the exchange); JSON envelopes with `type` and `seq`; prices are integer paise |
| 18 | Kafka | `apache/kafka` 4.x in KRaft mode, single node; topic `prayog.exchange.events.v1`, 3 partitions, keyed by symbol; JSON validated against JSON Schema; at-least-once delivery; `eventId` = exchange event sequence; consumers idempotent |
| 19 | Kafka catch-up | The publisher reads the event log from "last published sequence + 1", so Kafka downtime never stops trading and loses no events. Code comments explain offsets, at-least-once delivery and idempotency |
| 20 | Schemas | JSON Schema draft 2020-12 in `contracts/schemas/{rest,ws,events}/`; validated in tests by `com.networknt:json-schema-validator` (Java) and `jsonschema` (Python) |
| 21 | Auth | Keycloak 26.x, realm `prayog` imported from `deploy/compose/keycloak/`. Clients: `prayog-web` (public, PKCE), `prayog-api` (resource-server audience), `prayog-bot-demo` and `prayog-agents` (confidential, client credentials). A bot "API key" = Keycloak client ID + secret. Realm roles `trader`, `ops`, `bot`; seed users `trader1`, `ops1` (dev passwords in `.env`) |
| 22 | Rate limits | Token bucket per account in the gateway (no library); limits set by env var; agents get a high limit through config. A separate agent rate-limit tier is Phase 2 |
| 23 | Kill switch | Global (ops sets session HALTED) and per account (disable account, cancel its resting orders) |
| 24 | Infra images | `postgres:16-alpine`, `redis:7-alpine`, `apache/kafka:4.x`, Keycloak 26, Traefik v3; pinned |
| 25 | Redis / leaderboard | Lettuce; post-trade writes sorted set `prayog:leaderboard` and serves `GET /leaderboard` |
| 26 | Traefik | v3, HTTP only on host port 80; `app.`, `api.`, `auth.`, `grafana.`, `traefik.` `.prayog.localhost`; `/etc/hosts` entries documented for non-browser clients; `KC_HOSTNAME=http://auth.prayog.localhost` |
| 27 | Ports | Inside containers: exchange 8080, post-trade 8081, Keycloak 8080, web 80. Exposed to the host for dev: Postgres 5432, Kafka 9094, Redis 6379 |
| 28 | Compose | `deploy/compose/compose.yaml`; profiles `infra`, `app`, `obs`; health checks + `depends_on` conditions |
| 29 | Container images | Multi-stage; `eclipse-temurin:21-jre` runtime; non-root; `-XX:MaxRAMPercentage=75`; multi-arch through `docker buildx` (registry push deferred until hosting is decided) |
| 30 | Python | 3.12 (`.python-version`, installed by uv); one uv workspace at the root with `sdk/python` (`prayog_sdk`) and `services/agents`; Ruff, pytest, pytest-asyncio, httpx + websockets, pydantic, numpy |
| 31 | Web | Node 22 LTS; pnpm 10 through corepack; `pnpm-workspace.yaml` at the root; React 19, TypeScript strict, Vite, Redux Toolkit + RTK Query, react-router 7, `oidc-client-ts`, TradingView Lightweight Charts (attribution link required), ESLint, Prettier, Vitest, Testing Library |
| 32 | Makefile | `up`, `down`, `test`, `fmt`, `lint`, `build`, `logs`, `clean`, `help`; `bench` and `replay-check` added in S8/S9 |
| 33 | `make test` | `./mvnw -B verify`, `uv run pytest`, `pnpm -r test` |
| 34 | CI | GitHub Actions `ci.yml` on push and PR; parallel jobs: java (`./mvnw -B verify`), python (ruff check, ruff format --check, pytest), web (install with frozen lockfile, lint, typecheck, test, build) |
| 35 | ADRs | `docs/adr/NNNN-title.md` (Nygard format) |
| 36 | Git | GitHub repo `prayog`; `main` branch; conventional commits |
| 37 | Local tools | Homebrew: Docker Desktop, maven (only to create the wrapper once), uv, gh; pnpm through corepack; Python 3.12 through uv |

### 16.2 Approved additions to the dependency list (section 8)

networknt json-schema-validator, Python `jsonschema`, pytest-asyncio, `testcontainers-jooq-codegen-maven-plugin`, Spotless + palantir-java-format, ESLint, Prettier, Vitest, Testing Library, `oidc-client-ts`, TradingView Lightweight Charts. S14 analysis libraries (pandas, scipy, matplotlib) still need approval when S14 starts.

### 16.3 Rule clarifications

- A modify that reduces quantity to the filled amount or below becomes a cancel.
- A market order that finds no liquidity is cancelled with a reason.
- The band reference price is static per instrument for the MVP (no day rollover).

### 16.4 Milestones

Work proceeds by milestone. Sessions keep their section 10 definitions.

| Milestone | Sessions | Done when |
|---|---|---|
| **M0 Setup** | tool installs, git and GitHub setup, CLAUDE.md, PROGRESS.md, **S1** | `make test` passes on the empty skeleton, pushed to GitHub, CI green |
| **M1 First module** | **S3, S4** | `contracts` and `exchange-core` (limit matching) compile, unit and property tests pass, pushed |
| **M2 Engine complete** | **S5, S6, S7, S8** | All matching rules tested; Disruptor pipeline; replay checksum test passes in CI |
| **M3 Runnable app** | **S2, S10, S11, S12, S13, S15, S16, S17, S18, S19** | `make up`; sign in at `app.prayog.localhost`; trade by hand; the sample bot trades; P&L and leaderboard update |
| **M4 Hardening layers** | **S9, S14, S20, S21, S22** + multi-arch images + Phase 2 items (e.g. agent rate-limit tier) | MVP definition of done (section 11) met |
