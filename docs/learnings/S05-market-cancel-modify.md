# S5 Market orders, cancel and modify

**Built:**
- **Commands:** every input is a command passed to `MatchingEngine.apply`, and time arrives only through `ClockTick`.
- **Market orders:** they sweep the opposite side, and any remainder is cancelled with `NO_LIQUIDITY`.
- **Cancel and modify:** both check ownership, and modify applies the priority rules.

Commit `500edb6`; [ADR 0004](../adr/0004-commands-market-cancel-modify.md).

## Concepts

- [ ] **Commands and event sourcing.** Every change to engine state comes from a command, so the list of commands *is* the history: replay it and you get the same state. That's why even time is a command (`ClockTick`).
- [ ] **Sealed types.** `Command` and `ExchangeEvent` are sealed interfaces, so a `switch` over them must handle every type. Adding a new command makes the compiler point at every place that needs updating.
- [ ] **Modify carries the new *total* quantity.** Fills can happen while your modify is in flight. With "total = 8", the exchange computes open = 8 − filled at that moment, so you never end up with more than 8. With "open = 4" based on a stale screen, you could. FIX uses the same convention. You can't un-trade, so a total at or below the filled amount cancels the open part (`MODIFIED_TO_ZERO`).
- [ ] **Why reducing keeps priority and increasing loses it.** If increasing kept your place, you could queue 1 share early and later raise it to 10,000, jumping everyone who committed real size. Reducing only gives something away, so it keeps the spot. A price change means a different queue, so you join at the back, and you trade at once if the new price crosses.
- [ ] **Opaque errors prevent enumeration.** Cancelling someone else's order returns `UNKNOWN_ORDER`, the same as a missing order. A distinct "not yours" would let a bot probe every order ID and map other traders' resting orders. It's the same idea as "wrong username or password".
- [ ] **Reject vs. cancel.** A reject means "your input broke a rule"; a cancel with `NO_LIQUIDITY` means "valid order, but the market couldn't fill it". A market order on an empty book is accepted then cancelled. Bots react to the two differently, and every valid order gets an order ID for the audit trail.
- [ ] **Random tests only test what they reach.** A planted bug (reducing quantity lost priority) slipped past 1,000 random flows. Same-price modifies were about 1 in 21, and the bug also needed a queue behind the order and a later trade. Narrowing prices to 7 ticks fixed it. Shape generators so interesting cases are common, not just possible.
- [ ] **Mutation testing.** Plant small bugs deliberately and check that some test fails. This measures how strong the tests really are. Tools like PIT automate it for Java.
- [ ] **Layered tests.** Hand-written unit tests pin specific rules; property tests explore combinations. Each covers the other's blind spots: here the unit tests caught what the generator missed.

## In an interview

> "A modify carries the new total quantity, not the open quantity, because fills can race with the modify. The total is the trader's real intent: their maximum exposure."

> "I plant bugs to test the tests. One planted priority bug got past my property tests, which showed the random generator almost never reached that code path. I narrowed the input space and it was caught."
