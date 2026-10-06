# 14. Duplicate client order IDs are rejected per account per trading day

Date: 2026-10-07

## Status

Accepted

## Context

A client that times out waiting for `POST /orders` cannot tell whether its order arrived. If it simply sends again,
it may end up with two orders. Exchanges solve this with the client's own order ID (FIX `ClOrdID`): a repeated one is
refused. `RejectReason.DUPLICATE_CLIENT_ORDER_ID` was defined in the contracts in S3 but never enforced.

## Decisions

1. **Enforced in the matching engine** (`ClientOrderIds`), not the gateway, so the rule is journaled and replayed
   like every other rule, and holds across gateway restarts.
2. **Scope: per account, per trading day, accepted orders only.**
   - Per account: two accounts may use the same string.
   - Per day: the set is cleared when the session closes. Every order is a DAY order and expires then, which matches
     FIX practice.
   - Accepted only: a rejected request (say, off tick) may be corrected and resent under the same ID.
   - An ID stays used after its order fills or is cancelled.
3. **Order of checks:** unknown symbol, account disabled, session not open, **duplicate**, quantity, price. A
   duplicate is reported even if the retry also has a bad price, because "you already sent this" is the most useful
   answer.
4. **Bounded memory:** each account remembers its most recent 10,000 IDs (FIFO eviction). A retry is caught as long as
   fewer than 10,000 orders from the same account arrive in between. At the bots' rate limit (500 orders/s) that is
   20 seconds, far longer than any sensible retry delay.
5. **Deterministic:** hash lookups only, never iteration over a hash set; eviction follows arrival order.
6. **No rules version needed this time.** Changing an engine rule changes how old journals replay. Every client so far
   used random UUIDs (SDK, gateway default, self-test), so no existing journal contains a duplicate. The replay of
   the live journal (about 1.1 million commands) matched after the change. A future rule change that would alter
   history needs a rules version in `EngineSetup`.

## Testing

- `ExchangeRulesTest.DuplicateClientOrderIds`: retry refused with no second order; per account; rejected IDs stay
  free; IDs stay used after fill or cancel; a new day frees them; the per-account bound evicts oldest first.
- Property test: client IDs now come from a small pool per account, so duplicates occur in random flows; the
  reference matcher models the rule and must agree event for event.
- API: a retry over HTTP is `rejected` with `DUPLICATE_CLIENT_ORDER_ID`, and another account may use the same ID.
- Planted bug (not clearing at the close) caught by the unit and the property test.

## Trade-offs

- A client that reuses IDs on purpose (for example a counter that restarts) must start a new range each day.
- The SDK does not retry automatically; `docs/bots/api.md` explains the safe retry pattern.
