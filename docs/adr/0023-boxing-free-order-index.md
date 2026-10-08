# 23. A boxing-free order index; GC settings measured, not changed

Date: 2026-10-08

## Status

Accepted

## Context

The focus plan asked for GC tuning and lock-free changes, each with before and after numbers. Two facts were known:
GC pauses caused the order path's tail (S9 and the Docker investigation in `docs/benchmarks.md`), and matching is
already lock-free (one writer thread on a Disruptor ring, ADR 0006). So the question became: what does the matching
thread allocate, and which GC settings help on this host?

JFR allocation sampling on `MatchingEngineBenchmark` showed the order index, `HashMap<Long, RestingOrder>`, to be about
a third of the matching thread's bytes: a `HashMap$Node` on every put and a boxed `Long` on every put, get and remove
(order IDs are above the `Long` cache's 127).

## Options

1. **A primitive-keyed map from a library** (Agrona `Long2ObjectHashMap`, fastutil, Eclipse Collections). Proven code,
   but a new dependency, which CLAUDE.md asks to avoid without approval, for one class.
2. **A small textbook open-addressing map in `exchange-core`.** About 150 lines, tested against `HashMap` by a jqwik
   property.
3. **Leave it.** The engine is fast enough for a simulated market.

## Decision

Option 2: `LongObjectMap<V>`, package-private in `dev.prayog.exchange.core`.

1. Linear probing over a `long[]` of keys and an `Object[]` of values; capacity a power of two, at most half full;
   Fibonacci hashing spreads sequential order IDs.
2. Removal by backward shift (Knuth's Algorithm R), so there are no tombstones and lookups stay short under the
   constant churn of an order book.
3. Key 0 means empty, so 0 is rejected; order IDs start at 1.
4. Iteration order is unspecified. Only `OrderBook.ordersInIdOrder` iterates the index, and it sorts, so the event log
   cannot depend on the map's layout (determinism invariant).
5. **GC settings stay as they are.** On the host, settings changed pause counts reliably (fixed 512 MiB G1 heap: 6
   pauses instead of 11, longest 12 ms instead of 26 ms; ZGC: none), but the p99.9 tail varied 10x between repeats of
   one setting, so no setting can be shown to help it on this laptop. The container decision from the Docker
   investigation (memory first) stands.

## Consequences

- Allocation per operation fell 12-21% in every JMH benchmark; speed did not measurably change (interleaved A/B in
  `docs/benchmarks.md`).
- The order path's GC pauses did not change: the rest of its allocation is the engine's output (event records), the
  benchmark's commands, client order ID strings and price-level churn. Going further means flyweight events written
  into ring slots and pooled price levels, a larger redesign recorded as a next step, not done.
- One more data structure to own. The jqwik property (any sequence of puts and removes equals a `HashMap`'s result) and
  an allocation test (no bytes allocated on put, get and remove once sized) guard it.

## Testing

- `LongObjectMapTest`: unit tests, growth to 10,000 keys, the zero-key rule, zero allocation on the hot operations,
  and the property against `HashMap` with only 64 distinct keys so probe-chain removals are common.
- The whole `exchange-core` suite (matching properties, Cucumber rules, replay and snapshot tests) passes unchanged.
