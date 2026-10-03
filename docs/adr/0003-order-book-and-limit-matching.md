# 3. Order book and limit matching

Date: 2026-10-03

## Status

Accepted

## Context

S4 builds the first piece of the matching engine in `exchange-core`: a book per symbol and limit-order matching with price-time priority. It must follow the engine invariants: integer prices, one writer thread with no locks, sim time as an input, deterministic output, and no I/O.

## Decisions

1. **The book is a data structure; the rules live in `MatchingEngine`.** `OrderBook` only adds, fills and removes orders. Validation, crossing and event creation are in the engine. S5 (market, cancel, modify) and S6 (bands, sessions, self-trade prevention) add rules without changing the data structure. This split is standard in exchange design.
2. **Each side is a `TreeMap<Long, PriceLevel>`, sorted best first. Each level is an intrusive doubly linked FIFO list, and a `HashMap` indexes orders by ID.** This is the textbook limit order book. Best price in O(log n), append in O(1), and removal from the middle of a queue in O(1) once the order is found by ID, which S5 cancels need. A `TreeMap` boxes `long` keys and allocates map entries. The alternative is a primitive-keyed map (Agrona) or a price-indexed array. Per CLAUDE.md ("measure, don't guess"), that waits for S9 benchmarks to show it matters.
3. **A trade happens at the resting order's price.** The order that was on the book set the price; the incoming order may get a better price than its limit (price improvement). This is how continuous double auctions work on lit exchanges.
4. **Event order for one incoming order: `OrderAccepted`, then each `Trade` in fill order.** Clients see an acknowledgement before fills, as on real exchanges. Rejected orders get `OrderRejected` and do not use up an order ID.
5. **Validation returns the first failing reason in a fixed order** (symbol, quantity, price, tick). There is always exactly one deterministic reason.
6. **`Instrument.maxOrderQuantity` caps each order** (similar to NSE's freeze quantity). It also keeps level totals far below `long` overflow.
7. **The engine is single-threaded and owns all counters** (event sequence, order ID, trade ID), and never iterates a hash map to produce output. That makes the output deterministic. S7 puts it behind the Disruptor.
8. **Events go to an `EventSink` callback** rather than being returned as lists. There is no extra allocation per call, and S7 can plug the journal and fan-out in directly.
9. **Tests use an invariant checker and a reference model.** `OrderBook.checkInvariants()` walks the whole structure (links, totals, index, not crossed) after each step in tests. `ReferenceMatcher` is a deliberately naive matcher that scans a list; property tests require the engine's events to equal it exactly for random order flows. Model-based testing like this catches bugs that example tests miss. A planted bug (trading at the incoming price) failed 4 unit tests and 2 property tests.

## Deferred

- `BookUpdate` events (level deltas) are emitted from S11, when market data is built. The book already keeps the level totals they need.
- `MARKET` orders throw `UnsupportedOperationException` until S5.
- Duplicate client order ID checks: S10 decides whether the gateway or the engine owns them.

## Trade-offs

- `TreeMap` and boxed keys allocate on the order path, which creates garbage-collection pressure. This is accepted until benchmarks say otherwise.
- The reference matcher repeats the validation rules. If a rule changes, both must change. The property test fails loudly if they drift.
