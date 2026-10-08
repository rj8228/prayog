# Allocation profiling, GC settings and a boxing-free order index

**Built:** a JFR allocation profile of the matching thread, a GC settings matrix on the host, and `LongObjectMap`, an
open-addressing `long`-keyed map that replaces the order book's `HashMap<Long, RestingOrder>`
([ADR 0023](../adr/0023-boxing-free-order-index.md)). Allocation per operation fell 12-21%; speed and the order path's
GC pauses did not measurably change ([benchmarks](../benchmarks.md)). Also fixed: browser strategy accounts now get
names on the leaderboard.

## Concepts

- [ ] **Measure allocation, not just time.** JMH's `-prof gc` reports bytes per operation (`gc.alloc.rate.norm`) with
  an error under 1 B, while nanoseconds on a laptop drift by 20% or more. Bytes are the stable signal of a hot-path
  change; time needs interleaved A/B runs.
  *In an interview:* "I used allocation per op as the primary metric because it is deterministic, then checked time
  with interleaved runs to rule out a regression."
- [ ] **JFR allocation sampling.** `-XX:StartFlightRecording=settings=profile` samples allocations with their stack
  traces at low cost; grouping by class and first application frame names the line that allocates.
- [ ] **Boxing.** A `Map<Long, V>` turns every `long` key into a `Long` object (only -128..127 are cached) and every
  entry into a node. Primitive-keyed maps store keys in a `long[]`, so a lookup allocates nothing.
- [ ] **Open addressing and linear probing.** All entries live in one array; a collision moves to the next slot. Cache
  friendly and allocation free, but removals need care.
- [ ] **Backward-shift deletion (Knuth's Algorithm R).** After removing an entry, later entries of the same probe chain
  move back into the gap if their home slot allows it. No tombstones, so lookups don't slow down after heavy churn.
- [ ] **Fibonacci hashing.** Multiplying by 2^64/φ and taking the high bits spreads sequential keys (order IDs 1, 2,
  3...) evenly over a power-of-two table.
- [ ] **GC pauses vs tail latency.** Settings can change pause counts and lengths reliably, yet the tail can be
  dominated by something else (here, OS scheduling of busy-spinning threads). Measure both before claiming a fix.
- [ ] **Amdahl for allocation.** Removing a third of the engine's allocation changed nothing at the pipeline level,
  because in the pipeline the engine's index was a small share: event records, the benchmark's commands and price
  levels dominate.

## Explain-back: questions and model answers

### 1. Why did the order index allocate at all, when order IDs are just `long`s?

**What it's asking:** where boxing hides in ordinary Java code.

**Background:** Java generics work only with objects. `HashMap<Long, RestingOrder>.put(id, order)` converts the
primitive `id` into a `Long` object. `Long.valueOf` returns cached objects only for -128..127. Each put also creates a
`HashMap$Node` (key, value, hash, next pointer).

**Prayog example:** order IDs start at 1 and pass 127 within seconds, so every new resting order cost a `Long` (16
bytes) and a node (32 bytes), and every cancel or fill boxed the ID again to remove it. JFR put this at about a third
of the matching thread's bytes.

**Answer:** because the map's generic API forces `long` into `Long`, and a chained hash map needs a node per entry.
`LongObjectMap` stores keys in a `long[]` and values in an `Object[]`, so put, get and remove allocate nothing (a test
checks this with `ThreadMXBean.getCurrentThreadAllocatedBytes`).

### 2. Why can't a removal in a linear-probing table just clear the slot?

**What it's asking:** the one subtle part of open addressing.

**Background:** a lookup walks from the key's home slot until it finds the key or an empty slot. An empty slot means
"not here".

**Prayog example:** orders 5 and 9 both hash to slot 3; 5 sits in 3, 9 in 4. Cancel order 5 and clear slot 3: a lookup
for 9 starts at 3, sees empty and wrongly says "not on the book". The engine would then reject a valid cancel.

**Answer:** clearing would break chains. Either leave a tombstone (lookups skip it, but tombstones pile up under churn)
or shift later chain members back into the gap when their home slot allows it. `LongObjectMap.shiftBack` does the
second, and the jqwik property with only 64 distinct keys makes such chains common in tests.

### 3. Allocation fell 12-21%. Why did the order path's GC pauses not change?

**What it's asking:** whether you can explain a null result instead of hiding it.

**Background:** young-generation GC frequency follows total allocation rate; pause length follows how much survives.
A change only helps as much as its share of the total (Amdahl's law).

**Prayog example:** in `PipelineLatency`, JFR showed the event records (`OrderAccepted` 17%, `Trade` 14%), the
benchmark's own `NewOrder` commands (13%) and boxed `Long`s (5%), client order ID strings (7%) and price-level churn
(about 12%) dominating. The index was a few percent there, so 11 pauses became 10-11.

**Answer:** the engine benchmark isolates the matching thread; the pipeline allocates far more elsewhere. The next
real step is flyweight events written into ring slots and pooled price levels, which changes the engine's output API.
I recorded that as the next step rather than claim a GC win.

### 4. The fixed 512 MiB G1 heap halved pause count and length. Why not make it the default?

**What it's asking:** the difference between improving a metric and improving the outcome.

**Background:** G1 sizes the young generation to meet its pause goal; a larger, pre-touched heap means fewer
collections. But the tail you care about (p99.9 of order latency) has several causes.

**Prayog example:** p99.9 varied from 2.3 to 55 ms across three repeats of that same setting, and ZGC with no pauses
still hit 26.6 ms. Four busy-spinning pipeline threads compete with macOS for four performance cores. In the Docker
container, the earlier investigation found memory pressure, not the collector, was the cause.

**Answer:** pauses improved but the outcome did not measurably improve on this machine, and the container has only 768
MiB, so a 512 MiB fixed heap would starve native memory there. The decision stays: memory first, then compare
collectors on a quiet machine with isolated cores.

### 5. The sequential JMH run made the new map look 25% slower. How did you decide it wasn't?

**What it's asking:** benchmarking discipline.

**Background:** on a laptop, thermal state, background apps and turbo change over minutes. Running "before" then
"after" confounds the change with time.

**Prayog example:** sequentially, aggressive fill went 208 → 267 ns. Interleaved (after, before, after, before; three
forks each), place-and-cancel was 148, 179, 178, 183 ns and aggressive fill 213, 323, 287, 293 ns: "after" equal or
faster in every pair, with errors larger than the gaps.

**Answer:** I interleaved the variants with several forks so drift hits both equally, and relied on the deterministic
metric (bytes per op) for the claim. The honest summary is "allocation down, speed unchanged within noise".
