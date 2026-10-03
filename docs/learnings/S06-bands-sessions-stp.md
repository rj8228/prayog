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
