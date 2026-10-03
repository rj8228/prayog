# 6. Sequencer pipeline and simulated clock

Date: 2026-10-03

## Status

Accepted

## Context

S7 puts the engine behind a single-writer pipeline and makes time an input (BUILD_PLAN 16.1 #11, #13, #16). Decisions confirmed at the start of M2: the pipeline lives in `exchange-core` in its own package; the engine derives the open and close from clock ticks; clients get responses only after the journal is on disk (S8).

## Decisions

1. **Package layout.** `dev.prayog.exchange.core.pipeline` depends on the engine; the engine never depends on it or on the Disruptor. The engine stays a pure, synchronous library that tests can drive directly.
2. **LMAX Disruptor, `ProducerType.MULTI`, ring of 65,536 slots, wait strategy from config** (`BLOCKING` by default; `YIELDING` and `BUSY_SPIN` for benchmarks). The ring size must be a power of two so a sequence maps to a slot with a bit mask.
3. **Pre-allocated `CommandSlot`s.** A producer writes a command reference into a slot; the matching handler writes the input sequence and the command's events into the same slot; downstream stages read them. The event list is reused (cleared per command). A planted bug that skipped the clear was caught by the concurrency test.
4. **The input sequence is assigned on the matching thread** (`++counter` per slot), not by producers. The number and the processing order cannot disagree.
5. **Stages run strictly in order; handlers within a stage run in parallel.** Built with `builder(...).then(journal).then(publishers...)`. Downstream handlers use our own `PipelineHandler` interface, so the journal and publishers can be tested without the Disruptor. The `endOfBatch` flag is passed through so the journal can fsync once per batch.
6. **Back-pressure: `trySubmit` returns false when the ring is full**, so the gateway can answer "busy" instead of queueing without limit. `submit` waits; it is for internal producers (the clock, ops) that must not lose a command.
7. **Fail safe on handler exceptions** (`FatalExceptionHandler`): the failing handler's thread stops and the ring fills, so trading halts. An engine bug or a journal write failure must stop the market, not silently skip a command.
8. **`SimClock`: `simTime = anchor + wallElapsed × multiplier`, with an integer multiplier from 1 to 10,000.** Changing the multiplier re-anchors at the current sim time, so time never jumps. The wall clock is injected (`System::nanoTime` in production), and this is the only place that reads it.
9. **`ClockTicker` publishes a `ClockTick` every 100 ms** from its own thread. The multiplier changes what each tick says, not how often ticks come, so the journal grows at the same rate at any speed.
10. **`SessionSchedule` (open, close, fixed `ZoneOffset`).** The engine sets the session when a tick lands in a different scheduled period (each day has a "closed" and an "open" period). Between boundaries an ops override (early open, halt) stands. A tick that skips a whole night closes (expiring orders) before opening. A fixed offset rather than a zone with daylight-saving rules means sim time maps to time of day the same way forever.

## Testing

- **S7 acceptance:** 8 threads each submit 2,000 commands at the same moment. The recorded input sequence has no gaps, each thread's own order is preserved, and replaying the recorded inputs through a fresh engine on one thread gives identical events. It ran green 5 times in a row.
- Stage ordering: a later stage never sees a slot before an earlier stage has (10,000 slots).
- Back-pressure: with a stuck downstream stage and 16 slots, `trySubmit` refuses, and every accepted command is still processed afterwards.
- Clock: multiplier arithmetic, re-anchoring without jumps, and tick publishing.
- Schedule: open and close crossings, ops overrides, the overnight skip and validation.

## Trade-offs

- Commands are still allocated by producers (records). The slots are pre-allocated, but the commands are not; zero-allocation encoding would come with S9 benchmarks if needed.
- The engine is constructed inside the matching handler, so a pipeline owns exactly one engine and has no way to swap it. That's fine for one shard.
