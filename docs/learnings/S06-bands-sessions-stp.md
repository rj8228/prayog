# S6 Price bands, sessions, self-trade prevention, kill switch

**Built:**
- **Price bands:** limit orders outside the band are rejected, and market orders are bounded by the band edge.
- **Sessions:** CLOSED, OPEN and HALTED. HALTED accepts cancels only, and closing expires every live order.
- **Self-trade prevention:** cancel-incoming.
- **Kill switch:** per account.

[ADR 0005](../adr/0005-bands-sessions-stp-kill-switch.md).

## Concepts

- [ ] **Price bands (circuit filters).** Each stock may trade only within a percentage of its reference price today: NSE uses 2%, 5%, 10% or 20%. Bands limit damage from fat-finger mistakes and panic, since no single day can move a price beyond them.
- [ ] **Rounding band edges inward.** Low rounds up and high rounds down to the tick, so every price inside the band is valid and the edges are tradable. In integer maths, ceiling division is `(a + b − 1) / b` for positive numbers.
- [ ] **Fixed check order.** Symbol → account → session → quantity → price → tick → band. "May you trade at all?" comes before "is this order well formed?", and each reject has exactly one deterministic reason.
- [ ] **HALTED = cancels only.** People can reduce risk, but nothing new can trade. The book survives the halt.
- [ ] **DAY orders expire at the close, in a deterministic order.** The close walks books in symbol order (`TreeMap`), then orders by ID. Walking a `HashMap` here would make replays differ.
- [ ] **Self-trade prevention (STP).** Trading with yourself creates fake volume (a *wash trade*), which regulators forbid. With *cancel incoming*, matching stops at your own resting order and your incoming remainder is cancelled; earlier fills with others stand.
- [ ] **Kill switch.** The per-account emergency stop: cancel everything the account has open and reject anything new. It's the tool for a runaway bot (Knight Capital lost $440m in 45 minutes in 2012).
- [ ] **Commands as the audit trail.** Enabling or disabling an account emits no event, but the command itself is journaled (S8), so the history is complete.
- [ ] **Mutation-test every rule.** Each planted S6 bug was caught by a unit test *and* by the reference-matcher property. Tests that throw (for example a `ClassCastException`) show up as `ERROR`, not `FAILURE`; count both when checking.

## In an interview

> "Self-trade prevention is cancel-incoming: matching stops at the first resting order from the same account and cancels the incoming remainder. It's the common default, and a property test checks that no trade ever has the same buyer and seller."

> "End-of-day expiry walks books in symbol order and orders by ID, because iterating a hash map would make replays non-deterministic."

## Explain-back answers

1. **Why round band edges inward?** Rounding outward would let a price just outside the true band through (₹110.05 for a band ending at ₹110.033). Rounding to the nearest tick could do the same. Inward keeps every accepted price within the rule, at the cost of a band at most one tick narrower.
2. **Why a fixed check order, with state first?** One input must always give the same reject reason, because replays and client logic depend on it. State checks first also give the most useful answer ("market is halted" beats "price off tick" when nothing could trade anyway).
3. **Why cancel-incoming STP?** It's simple, it never touches someone's resting order unexpectedly, and the account that caused the conflict (by sending the new order) bears the outcome. Downside: a market maker's aggressive hedge can be cancelled by its own quote. Cancel-resting or cancel-both suit that case.
4. **Why no modifies while HALTED, even reductions?** Simplicity and safety: one rule ("only cancels") is easy to explain and test. A reduce-only modify is harmless and some venues allow it; it's a candidate for later, while a price change during a halt would create new trading interest.
5. **Why expire in a fixed order?** Each cancel gets the next sequence number. A different order on replay would shift every later event number, change the event-log checksum (S8), and break consumers that deduplicate by sequence.
