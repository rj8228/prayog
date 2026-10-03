# 5. Price bands, sessions, self-trade prevention and the kill switch

Date: 2026-10-03

## Status

Accepted

## Context

S6 adds the exchange's protective rules: price bands, session states (CLOSED, OPEN, HALTED), self-trade prevention, DAY-order expiry and the per-account kill switch (BUILD_PLAN section 5, 16.1 #23, 16.3). Decisions confirmed at the start of M2: HALTED allows cancels only; the open and close schedule is derived from clock ticks in S7.

## Decisions

1. **The band is a whole percent of a static reference price, with edges rounded inward to the tick.** Low = reference − band, rounded *up*; high = reference + band, rounded *down*. Every price inside the band is then valid and on tick, and the edges themselves are tradable. Integer arithmetic only, using ceiling division `(a + b − 1) / b`. An instrument whose band contains no valid price is refused at construction.
2. **Checks run in a fixed order: symbol, account enabled, session, quantity, price, tick, band.** State checks ("may you trade at all?") come before checks on the order's own fields, and tick comes before band. A reject always has one predictable reason.
3. **The engine starts CLOSED.** In S6, sessions change only through `SetSessionState` commands; S7 adds changes triggered by clock ticks. Setting the current state again is a no-op with no event.
4. **HALTED accepts cancels only.** New orders and modifies get `SESSION_NOT_OPEN`. People can reduce risk, but nothing new trades. The book is kept and trading resumes against it.
5. **Closing expires every live order (all orders are DAY orders).** `SessionStateChanged(CLOSED)` comes first, then one `OrderCancelled(EXPIRED)` per order, in symbol order, then order-ID order. Books are kept in a `TreeMap` by symbol so this walk is deterministic. Sorting the orders is fine for a once-a-day operation.
6. **Self-trade prevention: cancel the incoming order.** When the next resting order the incoming order would trade with belongs to the same account, matching stops and the incoming remainder is cancelled with `SELF_TRADE_PREVENTION`. Fills already made with other accounts stand, and the resting order is untouched. This applies to new limit orders, market orders and modifies that re-enter and cross. It is the simplest mode and the most common default; other modes (cancel the resting order, cancel both) exist on some venues.
7. **Market orders are bounded by the band edge** (buy: band high; sell: band low). With a static reference price no order can rest outside the band, so today this is a safety net. It matters once the reference price can move intraday.
8. **The kill switch disables an account and cancels all its live orders with `KILL_SWITCH`**, in symbol then order-ID order. New orders from that account get `ACCOUNT_DISABLED` until it is re-enabled. There is no per-account index; the walk over all books is acceptable for a rare ops action.
9. **There is no event for enabling or disabling an account.** The contracts have no such event, and the command itself is in the input journal (S8), which is the audit trail. Add an event if post-trade or the UI needs it.

## Testing

- `ExchangeRulesTest` covers each rule (bands, sessions, STP, expiry order, kill switch, check order).
- The reference matcher models all S6 rules independently, and random flows include session changes and kill switches.
- New properties: no account ever trades with itself; the book is empty whenever the market closes.
- Planted bugs: no STP, HALTED accepting orders, and expiry in reverse order. Each was caught by at least two tests.

## Trade-offs

- Cancel-incoming STP can surprise a market maker whose aggressive order is cancelled by its own quote. Other modes can be added per account later.
- Account disable is not visible as an event yet.
