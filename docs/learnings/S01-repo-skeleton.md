# S1 Repo skeleton and tooling

**Built:** a Maven multi-module repo (`contracts`, `exchange-core`, `exchange-app`, `exchange-bench`, `post-trade`), a uv workspace for Python, a pnpm workspace for the web app, a Makefile and GitHub Actions CI. Commit `1ba1cdf`; [ADR 0001](../adr/0001-spring-boot-4-and-maven.md).

## Concepts

- [ ] **Keeping the engine in a plain-Java module.** `exchange-core` has no Spring dependency, so Spring cannot creep into the engine. That rules out reaching the system clock, a database or the network, or adding hidden threads. The engine stays deterministic and testable with plain unit tests. The compiler enforces this, not code review.
- [ ] **What a Spring Boot parent POM gives.** Tested, compatible versions of Spring, Jackson, JUnit, Testcontainers and Maven plugins, plus defaults like UTF-8 and the Java release level. Libraries Boot doesn't manage (jqwik, Disruptor, JMH) need their versions pinned in our own `dependencyManagement`.
- [ ] **Why `./mvnw verify` and not `mvn test`.** The wrapper gives everyone the same Maven version (3.9.16) with no Maven install. `verify` goes past `test`: it packages, runs integration tests (`*IT`, via Failsafe) and runs the Spotless format check.
- [ ] **Lock files in CI.** `uv sync --locked` and `pnpm install --frozen-lockfile` fail if the lock file doesn't match the declared dependencies. CI never silently installs different versions than you tested locally; forgetting to commit the lock file breaks the build instead of quietly drifting.
- [ ] **Pinning GitHub Actions.** A moving major tag (`@v6`) is repointed to each new release, so you get fixes automatically but run whatever the maintainer publishes. A release tag (`@v10.2.0`) is fixed unless someone deliberately moves it. A full commit SHA can't change at all: the safest for supply-chain security, but you must update it yourself (Dependabot can). `setup-uv` publishes no moving major tag, which is why `@v10` failed.

## In an interview

> "The matching engine is a plain-Java module with no framework, so the build itself guarantees it can't touch the clock, network or database. That's what keeps it deterministic."
