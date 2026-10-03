# 2. Contracts and domain types

Date: 2026-10-03

## Status

Accepted

## Context

S3 defines the message shapes that every part of Prayog shares: REST requests, WebSocket market data and the exchange events that go to Kafka. Java, Python and TypeScript all read them. The engine invariants (integer money, determinism, no I/O on the order path) apply to the Java types.

## Decisions

Each decision is the common industry practice unless noted. The reason follows each one.

1. **Schemas live in `contracts/schemas/{rest,ws,events}/` at the top of the module and are also packaged into the jar under `schemas/`.** Python and the web app read the files from the repo; Java services load them from the classpath. One copy, no drift.
2. **Shared definitions live in `common.schema.json` and are referenced with `$ref`.** Each schema has an `$id` under `https://prayog.dev/schemas/`. The validator maps that prefix to `classpath:schemas/`, so it never fetches anything over the network.
3. **All integers have `maximum: 9007199254740991` (2^53 - 1).** JavaScript numbers are doubles. A larger integer would silently lose precision in the web app. This is the usual guard for JSON consumed by browsers.
4. **Prices are integer paise and quantities are integer shares, as `long` primitives in Java, not wrapper records.** This follows the engine invariant (no floating point for money). Primitives also avoid an allocation per value on the matching path. Java 21 has no value classes yet, so a `Price` record would be a heap object. The cost is weaker type safety, so names and Javadoc say the unit, and the unit is the same everywhere (paise, shares).
5. **Sim time is epoch microseconds (`simTime`).** Nanoseconds overflow the JavaScript safe-integer range (decision 3), and milliseconds are too coarse once the clock multiplier is above 1. Microseconds fit both.
6. **`0` means "none" for `price` (market orders) and `orderId` (a reject before an ID was assigned) in Java. In JSON the field is left out.** Low-latency Java code commonly uses sentinels instead of `Optional` or boxed values to avoid allocation. Valid prices and IDs start at 1, so `0` is never ambiguous.
7. **The event sequence `seq` is also the event ID.** BUILD_PLAN 16.1 #18 asks for `eventId` = exchange event sequence. One field avoids two numbers that must always be equal. Kafka consumers deduplicate on `seq`.
8. **Event `type` values are the Java record names (`OrderAccepted`, `Trade`, ...).** One naming scheme across Java, JSON and logs.
9. **The event schema uses `if type == X then $ref X` rather than a bare `oneOf`.** It gives one clear error for a malformed event instead of one error per event type.
10. **Request schemas use `additionalProperties: false`; event and market-data schemas allow extra fields.** Strict on input catches client typos. Tolerant on output lets us add fields without breaking older consumers. This is the robustness principle as commonly applied to versioned message contracts.
11. **Public market data never carries account IDs.** A trade print on the WebSocket has no `buyAccountId` or `sellAccountId`; only the Kafka `Trade` event (which feeds post-trade) has them. Anonymity of counterparties is standard on lit exchanges.
12. **The REST error body follows RFC 9457 (Problem Details).** It is the standard error format for HTTP APIs, and Spring supports it natively.
13. **Java enums and event records live in `contracts` with no runtime dependencies.** The engine (`exchange-core`) depends on `contracts` and emits these records directly, so there is no mapping layer between engine and journal or Kafka. Jackson and the validator are test-only in `contracts`, so the engine stays free of libraries.
14. **Tests keep Java and the schemas in sync.** Every schema has valid and invalid samples. Every Java enum must match its schema enum, and every event record's fields must match its schema's properties.

## Deferred

- REST order responses and the WebSocket private order channel are shaped in S10 and S11, when the gateway semantics are known.
- Serialising Java records to JSON (Jackson 3 in `exchange-app`) and validating the output against these schemas comes in S10 and S15.

## Trade-offs

- Primitive `long` prices can be mixed up with quantities by mistake. Mitigation: consistent naming, and property tests in S4 that check conservation of quantity and price bounds.
- The `0` sentinel needs care at the JSON boundary. The mapping rule is in decision 6.
