# 14. Duplicate client order IDs, matching-rules versions, verified recovery

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
6. **Matching-rules versions, switched by a journaled command.** The first attempt enabled the rule for all
   history. That was wrong: `smoke.sh` always sends the client order ID `smoke`, so the live journal held accepted
   duplicates. Replaying it under the new rule rejected one of them, and every later order ID and event shifted.
   So rule changes are now inputs:
   - `SetRules(version)` is a command, journaled like any other. A new engine starts at version 1 (the original
     rules). The exchange journals `SetRules(MatchingEngine.LATEST_RULES)` each time it starts; it is a no-op once the
     engine is there.
   - History before the command replays under the rules it was made with, and everything after it under the new
     ones. This is how real venues change matching behaviour: from a given point, never retroactively.
7. **Recovery verifies the event log.** Recovery used to check only that replay produced *at least* as many events as
   the log held, so the bad first attempt started without complaint. It ran for about two minutes on a book that
   differed from what clients had been told, and appended 191 events that never happened. Now every replayed event
   must match the recorded one byte for byte, or the exchange refuses to start. Repair: the input journal is the
   source of truth and the event log is derived, so move the event log aside and let recovery regenerate it
   (runbook 7).

## Testing

- `ExchangeRulesTest.DuplicateClientOrderIds`: retry refused with no second order; per account; rejected IDs stay
  free; IDs stay used after fill or cancel; a new day frees them; the per-account bound evicts oldest first.
- Property test: client IDs now come from a small pool per account, so duplicates occur in random flows; the
  reference matcher models the rule and must agree event for event.
- API: a retry over HTTP is `rejected` with `DUPLICATE_CLIENT_ORDER_ID`, and another account may use the same ID.
- Planted bug (not clearing at the close) caught by the unit and the property test.
- Rules versions: a version-1 engine accepts duplicates, `SetRules(2)` switches the rule on from that command, and
  versions only move forward.
- Recovery: an event log with one doctored event is refused, naming the seq; planted bug (skip the comparison)
  caught.

## Trade-offs

- A client that reuses IDs on purpose (for example a counter that restarts) must start a new range each day.
- The SDK does not retry automatically; `docs/bots/api.md` explains the safe retry pattern.
