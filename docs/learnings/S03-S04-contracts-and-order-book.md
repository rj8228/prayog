# S3–S4 Contracts and the order book

**Built:**
- **S3:** JSON Schemas for REST, WebSocket and Kafka messages, with valid and invalid samples, plus Java enums and event records kept in sync with the schemas by tests.
- **S4:** the order book and limit-order matching in `exchange-core`, with unit tests, property tests and a reference matcher.

Commits `a410d66` and `ef259c6`; [ADR 0002](../adr/0002-contracts-and-domain-types.md) and [ADR 0003](../adr/0003-order-book-and-limit-matching.md).

## Contracts (S3)

- [ ] **Integer money.** Prices are whole paise in a `long`. Floating point can't represent 0.10 exactly, so sums drift (₹0.1 + ₹0.2 ≠ ₹0.3), and P&L ends up off by fractions that never reconcile.
- [ ] **The 2^53 cap.** JavaScript numbers are doubles, which hold integers exactly only up to 9,007,199,254,740,991. Every schema integer has that maximum, so the web app can never silently corrupt an ID or a price.
- [ ] **Microseconds for sim time.** Nanoseconds since 1970 exceed the 2^53 cap; milliseconds are too coarse once the clock runs faster than 1×.
- [ ] **`0` as "none".** Low-latency Java avoids `Optional` and boxed values on the hot path. Valid prices and IDs start at 1, so `0` can safely mean "no price" (a market order) or "no order ID". In JSON the field is left out instead.
- [ ] **Strict input, tolerant output.** Requests reject unknown fields (catches typos); events allow them (new fields don't break old consumers).
- [ ] **Contract tests both ways.** Valid samples must pass and invalid samples must fail, each breaking exactly one rule. A sync test fails if a Java enum or record drifts from its schema.

## Order book and matching (S4)

- [ ] **Price-time priority.** Best price first; at one price, oldest first. It rewards whoever offers the best price, and then whoever offered it first.
- [ ] **A trade happens at the resting order's price.** The resting order made the public offer and the incoming order accepted it. Example: an ask at ₹100.00 meets a buy limit of ₹100.50; the trade is at ₹100.00, and the buyer gets 50 paise of *price improvement*. A limit means "no worse than", not "exactly".
- [ ] **Maker and taker.** The resting order is the maker (it provided liquidity); the incoming order is the taker (the aggressor).
- [ ] **The order book data structure.** Each side is a sorted map of price levels (best first). Each level is a doubly linked FIFO list of orders, plus a hash map from order ID to order. Finding the best price takes O(log levels); a fill is O(1); a cancel is O(1), via the hash map and unlinking.
- [ ] **Why not an `ArrayList` per level.** `remove(0)` shifts every element left, and a cancel from the middle needs a search plus a shift. Positions also change on every shift, which breaks any index. Real order flow is mostly cancels, so cancel speed is the common case.
- [ ] **Intrusive list.** The order *is* the list node (it holds `prev` and `next`). Java's `LinkedList.remove(object)` would still search from the start.
- [ ] **Never iterate a `HashMap` to produce output.** Its iteration order is unspecified and can change with the JDK version or the map's internal size. That would break "same inputs → byte-identical events". Use ordered structures when order matters.
- [ ] **Book data vs. matching rules.** `OrderBook` only stores and changes orders; `MatchingEngine` applies the rules. New rules don't touch the data structure.
- [ ] **Property-based testing (jqwik).** Generate thousands of random order flows and check rules that must always hold: the book is never crossed, quantity is conserved, sequence numbers have no gaps, runs are deterministic. A failing flow is shrunk to its shortest form.
- [ ] **Reference model (test oracle).** A deliberately naive matcher must produce identical events. It catches bugs that are plausible but wrong. Example: filling the *newest* order first at a level passes every property check but is caught by the reference model.
- [ ] **Invariant checker.** `checkInvariants()` walks the whole book after every test step: links, totals, the index, not crossed. Far too slow for production, but it catches corrupted structures immediately in tests.

## In an interview

> "The book is the textbook design: a sorted map of price levels, each an intrusive FIFO list, plus a hash index by order ID, so cancels are O(1). I test it with jqwik properties and a naive reference matcher that must produce byte-identical events."

> "Public trade prints carry no account IDs; only the internal Kafka event does, because post-trade needs both sides. It's need-to-know, and the schema enforces it."
