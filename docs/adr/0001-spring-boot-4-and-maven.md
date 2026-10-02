# 1. Spring Boot 4 and Maven

Date: 2026-10-03

## Status

Accepted

## Context

The plan (v1.1) named Spring Boot 3 and Maven. By October 2026 Spring Boot 4.x is the current line, and free support for 3.5 is ending or has ended. Prayog is a new codebase with no migration cost.

## Options

1. Spring Boot 3.5.x as originally written: familiar, but on an ageing support line.
2. Spring Boot 4.x: current line; Jakarta EE 11, Spring Framework 7, Jackson 3, JUnit 6, modular starters.

Build tool: Maven (multi-module, Maven Wrapper) was already decided over Gradle.

## Decision

Spring Boot 4.1.1 via `spring-boot-starter-parent`, Maven 3.9.16 through the Maven Wrapper. The root POM is both parent and aggregator; the engine (`exchange-core`) is plain Java with no Spring dependency.

## Trade-offs

- Fewer tutorials and answers online for Boot 4; Jackson 3 changes package names (`tools.jackson`).
- Some third-party plugins lag behind (see the S16 risk in BUILD_PLAN section 16.5).
- In exchange: current support, current libraries, and one less upgrade to do later.
