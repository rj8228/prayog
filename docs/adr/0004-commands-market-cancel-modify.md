# 4. Commands, market orders, cancel and modify

Date: 2026-10-03

## Status

Accepted

## Context

S5 adds market orders, cancel and modify (BUILD_PLAN section 5 and 16.3). It also gives the engine its final input shape, because S7 (pipeline) and S8 (journal) need every input to be one replayable command.

## Decisions

1. **One sealed `Command` type and one entry point, `MatchingEngine.apply(Command)`.** The commands are `NewOrder`, `CancelOrder`, `ModifyOrder`, `ClockTick`, `SetSessionState` and `SetAccountEnabled`. Every state change comes from a command, so the input journal (S8) is simply the list of commands. A sealed type makes the compiler check that every `switch` handles every command. This is the standard event-sourcing shape.
2. **Time enters only through `ClockTick`; it never goes backwards.** A tick earlier than the current time is ignored rather than rejected. The clock thread is ours, so a backwards tick is a bug, but the engine must never throw on input. Events carry the time of the last tick.
3. **A market order sweeps the opposite side, and any remainder is cancelled with `NO_LIQUIDITY`.** It is accepted first (with price 0), then trades, then is cancelled. It never rests. Until S6 adds price bands its limit is unbounded; S6 replaces that with the band edge.
4. **Cancel and modify name the order by ID plus symbol.** The symbol picks the book without a global index, as FIX cancel requests do. An unknown symbol gives `UNKNOWN_SYMBOL`; an unknown order gives `UNKNOWN_ORDER`.
5. **Another account's order is reported as `UNKNOWN_ORDER`, exactly like a missing one.** A different error would let anyone probe which order IDs exist. This is a common security practice: don't reveal what you are not allowed to touch.
6. **Modify carries the new *total* quantity, including what has filled.** This is the FIX convention. `leavesQuantity` in `OrderModified` is what remains open.
7. **Priority on modify.** Reducing quantity at the same price keeps the order's queue position, since it harms nobody behind it. Increasing quantity or changing price removes the order and re-enters it at the back, and it may trade at once if the new price crosses. In that case `OrderModified` comes first, then any `Trade`s. A modify with unchanged values is accepted and keeps priority.
8. **A modify to the filled amount or below cancels the open part with `MODIFIED_TO_ZERO`** (BUILD_PLAN 16.3).
9. **A rejected modify leaves the order untouched**, and the reject carries the order ID.
10. **`EXPIRED` is added to the cancel reasons now** (schema and enum) for DAY expiry in S6, so the contract changes once.

## Testing

- Unit tests cover each rule: `MarketOrderTest` and `CancelModifyTest`.
- The reference matcher models cancel, modify and market orders independently, using an explicit arrival counter for time priority.
- Property tests run random flows of limits, markets, cancels, modifies, ticks and bad inputs. Conservation per order is checked as `ordered = filled + open + cancelled`.
- Two planted bugs were caught: losing priority on a reduction, and a silent market remainder. The first one exposed a weak generator: prices spanned 21 ticks, so same-price modifies were rare and the property tests missed the bug. Narrowing prices to 7 ticks fixed that. The lesson: check that random tests actually reach each code path, by planting a bug in it.

## Trade-offs

- An unbounded market order can sweep a thin book to absurd prices until S6 adds bands.
- Modify by remove-and-re-add allocates a new `RestingOrder`. That is acceptable until benchmarks say otherwise.
