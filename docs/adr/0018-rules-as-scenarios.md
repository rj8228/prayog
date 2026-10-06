# 18. Exchange rules as Cucumber scenarios

Date: 2026-10-07

## Status

Accepted

## Context

S20 asks for the key matching rules written as Given/When/Then and run in CI. The rules were already tested
(example tests, property tests against a reference matcher), but as Java that only a developer reads. A rulebook a
non-developer can check is a different artefact: it says what the exchange promises.

## Decisions

1. **Cucumber-JVM 8 on the JUnit Platform**, in `exchange-core`'s tests, so `./mvnw verify` (and CI) runs the scenarios
   with every build. There is no separate runner. The HTML report goes to
   `exchange-core/target/cucumber-rules.html`.
2. **Against the engine itself**, not the HTTP API. The rules are the engine's, the scenarios run in milliseconds, and
   the API is covered by its own tests and by `make e2e`.
3. **A small, plain vocabulary.** People are named accounts, and prices are written in rupees ("1500.05", converted
   exactly to paise). The book is checked as a table of price, quantity and orders. "Then" steps look only at what
   the last action did. Twenty-two scenarios in four features: price-time priority, market orders, cancel and modify,
   and pre-trade checks with sessions, kill switch, self-trade prevention and duplicate client order IDs.

## Testing

- All 22 scenarios pass. Planted bug (trade at the incoming order's price instead of the resting one): 4 scenarios
  fail, each naming the trade it expected.

## Trade-offs

- Scenarios duplicate some example tests. They are kept because they are the readable rulebook; the property tests
  remain the deeper check.
