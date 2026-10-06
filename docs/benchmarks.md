# Benchmarks

Every performance number in this project comes from here (CLAUDE.md: measure, don't guess). Each entry says the
command, the machine and the date. A laptop is a noisy machine: the error bars are part of the result.

**Machine for all entries below:** Apple M1 (4 performance + 4 efficiency cores), 8 GB RAM, macOS 26.5.2, Oracle JDK
21.0.6, plugged in, other apps running (an ordinary development laptop, not a tuned server).

## 2026-10-07 · S9 · Matching engine alone (JMH)

`make bench` (`MatchingEngineBenchmark`: one thread, no journal, events consumed by a JMH Blackhole; 3 × 2 s warm-up,
5 × 2 s measured, 1 fork, G1). `depth` = price levels per side, 4 resting orders each.

| Operation | depth 10 | depth 1,000 |
|---|---|---|
| Passive limit order joins the best bid, then is cancelled (2 commands) | 123 ± 25 ns | 142 ± 32 ns |
| Aggressive limit buys 10 from the best ask, a new ask refills it (2 commands, 1 trade) | 237 ± 146 ns | 225 ± 56 ns |
| Market order sweeps 3 levels (12 fills), 12 asks refill them (13 commands) | 2,047 ± 214 ns | 2,177 ± 472 ns |

Reading: about 60-120 ns per command on the matching thread, so the engine alone could take millions of commands a
second. Book depth barely matters: the best level is found through a `TreeMap` (O(log n)) and orders sit in
intrusive linked lists, so a cancel never walks the queue.

## 2026-10-07 · S9 · Order path end to end (HdrHistogram)

`make bench-latency ARGS="<rate> <seconds> <wait strategy> [nojournal]"` (`PipelineLatency`): submit → matching →
journal (fsync at the end of every batch) → the stage that would answer the client. No network, no JSON. Commands are
sent at a fixed rate; latency is measured from each command's **intended** send time (no coordinated omission).
5 s warm-up, then 15-20 s measured.

| Run | p50 | p90 | p99 | p99.9 | max |
|---|---|---|---|---|---|
| 1,000/s, BLOCKING, journal on | 12.0 ms | 15.1 ms | 35.0 ms | 71.0 ms | 83.8 ms |
| 20,000/s, BLOCKING, journal on | 12.7 ms | 20.9 ms | 140.8 ms | 286.5 ms | 300.2 ms |
| 20,000/s, BLOCKING, journal off | 23.8 µs | 956 µs | 17.3 ms | 59.6 ms | 74.6 ms |
| 20,000/s, BUSY_SPIN, journal off | 0.5 µs | 50.5 µs | 13.5 ms | 118.7 ms | 133.6 ms |
| 20,000/s, BUSY_SPIN, journal off, `-XX:+UseZGC` | 0.5 µs | 2.1 µs | 1.3 ms | 6.9 ms | 19.8 ms |

Reading:
- **The disk dominates.** With the journal, the median is about 12 ms at any rate. That is the cost of `fsync` on
  this Mac's SSD, and every command waits for one, because a client is told nothing a crash could erase (ADR 0006,
  0007). At 20,000/s group commit keeps up: one fsync covers hundreds of commands, so throughput holds while
  latency stays at the fsync floor.
- **Without the disk, the pipeline itself is fast:** half a microsecond median with busy-spinning threads, about
  24 µs when threads sleep and must be woken (BLOCKING, the default, which leaves the CPU free).
- **The tail is the garbage collector.** `-Xlog:gc` showed 10 G1 pauses of up to 26 ms in a 20 s run. ZGC (concurrent,
  sub-millisecond pauses) cut p90 from 50 µs to 2 µs and p99 from 13.5 ms to 1.3 ms. The rest of the tail is the
  allocation on the order path (event records, boxed contexts) and macOS scheduling.
- **Next steps if latency mattered:** a server with a fast fsync (enterprise NVMe with power-loss protection, or a
  replicated journal instead of fsync, as LMAX does); ZGC or an allocation-free hot path; pinned cores. None are
  needed for a simulated market at tens of commands a second.
