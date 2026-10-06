# S20 Exchange rules as scenarios

**Built:** 22 Cucumber scenarios in `services/exchange/exchange-core/src/test/resources/features/` describe the
matching rules in plain English and run against the real engine on every build ([ADR 0018](../adr/0018-rules-as-scenarios.md)).

## Concepts

- [ ] **Executable specification.** A rule written so that a person can read it and a machine can check it. If the code
  changes the rule, the build fails with the sentence that is no longer true.
- [ ] **Gherkin.** `Feature`, `Background` (shared setup), `Scenario`, `Given` (state), `When` (action), `Then`
  (outcome), and `Scenario Outline` with `Examples` for one rule over many inputs.
- [ ] **Step definitions.** Regular expressions that map each sentence to code. A small vocabulary keeps the scenarios
  consistent and the glue code short.
- [ ] **Scope.** Scenarios check behaviour visible to a participant (trades, book, rejections), not internals.

## Explain-back: questions and model answers

### 1. The engine already had example and property tests. What do the scenarios add?

**Answer:** Audience and intent. The Java tests prove the code works; the scenarios state what the exchange promises,
in words a product owner, a compliance officer or a new developer can check without reading Java. "At the same price,
the earlier order trades first" with alice, bob and carol is the rulebook. Because it runs on every build, the
rulebook cannot drift from the code. The property tests stay as the deeper net for combinations no one would write
by hand.

### 2. Why test the rules against the engine and not through the HTTP API?

**Answer:** The rules live in the engine, and testing them there makes each scenario take microseconds and fail with a
precise message. Going through HTTP would add authentication, JSON and timing to every scenario without testing the
rules any better. The API has its own tests, and `make e2e` checks the whole system.

### 3. How do the "Then" steps know which events belong to the step they check?

**Answer:** Every action step records where the event stream stood before it ran (`mark`). The outcome steps look only
at the events after that mark, which are exactly the events the last action produced. So "When carol places a BUY
limit...; Then carol buys 10 at 1500.00 from alice" cannot be satisfied by an older trade.

### 4. What does a Scenario Outline buy us in the pre-trade checks feature?

**Answer:** One rule ("bad orders are rejected with one clear reason") over a table of inputs: off tick, above and
below the band, zero quantity. Each row runs as its own scenario and is reported separately, so adding a case is one
line. A companion scenario checks that the band edges themselves are accepted. Boundaries are where off-by-one bugs
live.

### 5. How do we know the scenarios can fail?

**Answer:** A planted bug made trades happen at the incoming order's price instead of the resting order's. Four
scenarios failed, each with its sentence, for example "Then carol buys 10 at 1500.00 from bob". Tests that never fail
when the code is wrong are worth nothing, so planting a bug is part of finishing every test suite in this project.

## In an interview

"The matching rules are also a Cucumber rulebook: 22 Given/When/Then scenarios in plain English, run against the
engine on every build. They are the readable specification; property tests against a naive reference matcher are the
deep check."
