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

## 2026-10-08 · Journal archiving (ADR 0022)

Machine: Apple M1, 8 GB, macOS 26 (Darwin 25.5), Java 21.0.6, SSD. Data: a copy of the live journal volume
(`prayog_exchange-journal`, about 3 days of sessions), one closed 64 MiB segment per journal. Each full read was run 3
times; the first live read is cold (pages not yet cached).

```
java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  -cp "services/exchange/exchange-core/target/classes:<exchange-core classpath>" ArchiveBench.java <journal copy>
```

(`ArchiveBench` copies a journal's segments to a temp dir, times `JournalReader.read` over everything, runs
`JournalArchiver.archive(dir, name, Long.MAX_VALUE)`, then times the full read again.)

| Journal | Records | Segment | Archived | Ratio | Archive time | Full read live | Full read after |
|---|---|---|---|---|---|---|---|
| input | 1,319,912 | 64.0 MiB | 24.3 MiB | 2.6x | 3.3 s | 1,524 / 56 / 32 ms | 490 / 305 / 284 ms |
| events | 1,002,939 | 64.0 MiB | 13.3 MiB | 4.8x | 2.5 s | 33 / 31 / 31 ms | 214 / 189 / 172 ms |

Reading:
- **Disk use falls by 2.6-4.8x** for archived history. The input journal compresses less because order commands
  carry more varied fields; events repeat structure (fills, acks).
- **Archiving costs about 3 s per 64 MiB segment** (gzip, then a byte-for-byte check). It runs on the snapshot thread,
  never on the order path, at most once per finished segment.
- **Reading archived history is 6-9x slower** (gunzip, about 0.2-0.3 s per segment, warm). Only full replays (the
  replay check, admin views, recovery when no snapshot is usable) read it; restart from a snapshot and the Kafka
  publisher read live segments only, which is why the safe point is the oldest kept snapshot and the Kafka checkpoint.
- **Memory (after the fix in ADR 0022):** `java -Xmx96m ... ArchiveSmallHeap.java <journal copy>` archives both 64 MiB
  segments (6.7 s and 5.3 s under that heap); the first version of the check threw `OutOfMemoryError` with the same
  heap.

## 2026-10-08 · Order latency tail in the Docker stack (Grafana p99 of 445 ms, spikes to 3.5 s)

Question: Grafana showed new-order p99 at 445 ms with spikes to about 3.5 s on 2026-10-07 (03:05-03:18 IST), against
12 ms median for the order path on the host (above). Where does the tail come from?

Machine: as above; Docker Desktop with 3.8 GiB for the whole stack (11 containers); the exchange container has
768 MiB and `-XX:MaxRAMPercentage=75`. Load: the simulated traders, calm scenario, clock at 1x (about 1.7-1.8 new
orders a second). Numbers from Prometheus (`prayog_order_latency_seconds`, gateway to journaled answer;
`jvm_gc_pause_seconds`), queried by `docs/_tools/gc_latency_window.py <end time> <window>` (histogram_quantile over the window).

| Window | Collector | p50 | p90 | p99 | p99.9 | Longest GC pause | GC pause time in window |
|---|---|---|---|---|---|---|---|
| 2026-10-06 21:36-21:44 UTC (the screenshot) | Serial | - | - | 410 ms - 1.41 s | 524 ms - 3.47 s | 5.93 s full, 1.07 s minor | 6.3 s of minor pauses in 4 min (about 94 ms each) |
| 2026-10-07 19:50-20:00 UTC | Serial | 2.8 ms | 5.5 ms | 14 ms | 25 ms | 44 ms | 2.7 s (about 15 ms each) |
| 2026-10-07 20:01-20:11 UTC | Generational ZGC (`-XX:+UseZGC -XX:+ZGenerational`) | 2.9 ms | 7.0 ms | 78 ms | 156 ms | 10 ms | 14 ms |

Also measured: fsync in the journal volume inside the container, `dd bs=4k count=200 oflag=dsync`: 0.8 ms each.

Reading:
- **The JVM picks the Serial collector in this container** (less than 1792 MB of memory), whose pauses stop every
  thread, including the matching thread and the journal.
- **The screenshot's tail was GC pauses, about 6x slower than normal.** Collections were as frequent as in the clean
  window but each took about 94 ms instead of 15 ms, and one full collection took 5.9 s. Same work, slower pauses: most
  likely the host was short of memory (8 GB Mac, browser recording the demo, 11 containers). Not proven: there is no
  host memory metric in Prometheus.
- **In steady state the tail is fine:** p99 14 ms, p99.9 25 ms, close to the host's fsync-bound path.
- **ZGC made it worse here.** Its pauses are tiny, but it used the whole 512 MiB heap, the container ran at
  758/768 MiB, and allocation stalls (not counted as pauses) raised p99 5x. A concurrent collector needs headroom.
- **Decision:** keep the default collector. If the tail matters again: give Docker more memory first, then compare G1
  and ZGC with a larger container (for example 1.5 GiB), measured the same way.

## 2026-10-08 · GC settings and a boxing-free order index (ADR 0023)

Machine: as above, Docker stack stopped (`make down`) so nothing else competes for the 8 GB. Two questions: which GC
settings shorten the order path's tail on this host, and how much of the matching thread's allocation can go.

### GC settings on the host

`PipelineLatency 20000 20 BUSY_SPIN nojournal` (journal off so GC, not fsync, is what shows), run directly with
`java --add-exports java.base/jdk.internal.misc=ALL-UNNAMED <flags> -Xlog:gc:file=gc.log -cp <bench classpath>
dev.prayog.exchange.bench.PipelineLatency ...`. Three repeats of six settings, interleaved (one full round of all six,
then the next).

| Setting | GC pauses per run | Longest pause | p99 (3 runs) | p99.9 (3 runs) |
|---|---|---|---|---|
| G1, default heap | 11, 11, 11 | 25-26 ms | 0.02-0.67 ms | 7.2-11.9 ms |
| G1, `-Xms512m -Xmx512m -XX:+AlwaysPreTouch` | 6, 6, 6 | 10.6-11.9 ms | 0.01-11.7 ms | 2.3-55.3 ms |
| G1, 512 MiB, `-XX:MaxGCPauseMillis=5` | 9, 9, 9 | 12 ms | 0.01-0.02 ms | 4.4-13.7 ms |
| Parallel, 512 MiB | 1, 1, 1 | 12-13 ms | 0.01-0.48 ms | 1.5-17.4 ms |
| Serial, `-Xmx384m` (like the container) | 6, 6, 6 | 17-99 ms | 0.01-136 ms | 8.3-230 ms |
| Generational ZGC, 512 MiB | 0 stop-the-world pauses | - | 0.01-8.0 ms | 1.9-26.6 ms |

Reading:
- **GC settings change the pauses reliably:** a fixed 512 MiB heap halves G1's pause count and longest pause; Parallel
  collects once per run; ZGC has no stop-the-world pause.
- **They do not reliably change the tail on this laptop.** p99.9 swings 10x between repeats of the same setting, and
  ZGC with no pauses still reached 26.6 ms. With four busy-spinning threads on four performance cores, macOS
  scheduling is now the larger source of the tail. A tuned server (isolated cores, no other apps) is needed to rank
  collectors by tail; on this machine only the pause numbers are trustworthy.
- **Serial in a small heap is the one clearly bad choice** (a 99 ms pause and p99.9 of 230 ms in one run), which agrees
  with the Docker investigation above.
- **Decision:** none for the container yet (see the entry above: memory first). For the host benchmarks, a fixed heap
  with pre-touch makes pause behaviour repeatable.

### Where the matching thread allocates (JFR)

`MatchingEngineBenchmark` (place-and-cancel and aggressive fill, depth 10) with
`-jvmArgsAppend "-XX:StartFlightRecording=filename=alloc.jfr,settings=profile"`, then
`jfr print --json --events jdk.ObjectAllocationSample alloc.jfr`, grouped by class and first Prayog frame:

| Share of bytes | What | Where |
|---|---|---|
| 23.1% | `HashMap$Node` | `OrderBook.add` (`ordersById.put`) |
| 18.9% | `RestingOrder` | `MatchingEngine.newOrder` |
| 15.5% | `OrderCancelled` | `MatchingEngine.cancelled` |
| 10.9% | `OrderAccepted` | `MatchingEngine.newOrder` |
| 8.3% | `HashMap$Node` | `ClientOrderIds.add` |
| 7.4% + 2.3% + 1.6% | `Long` (boxing) | `OrderBook.remove`, `order`, `add` |
| 3.8% | `SimpleImmutableEntry` | `OrderBook.bestLevel` (`TreeMap.firstEntry`) |

The order index (`HashMap<Long, RestingOrder>`) is about a third of all bytes. Events and resting orders are the
engine's output and state; the index is pure overhead. ADR 0023 replaces it with `LongObjectMap` (open addressing over
a `long[]` and an `Object[]`, no boxing, no nodes).

### Before and after: matching engine (JMH, `-prof gc`)

Same settings as S9 (`-f 1 -wi 3 -w 2 -i 5 -r 2`), before and after run back to back with each version's classes
first on the classpath.

| Operation | depth | B/op before | B/op after | Change | ns/op before | ns/op after |
|---|---|---|---|---|---|---|
| Place passive, then cancel | 10 | 496 | 392 | -21% | 115 ± 14 | 134 ± 8 |
| Place passive, then cancel | 1,000 | 497 | 392 | -21% | 193 ± 204 | 148 ± 3 |
| Aggressive fill and refill | 10 | 656 | 576 | -12% | 189 ± 8 | 294 ± 261 |
| Aggressive fill and refill | 1,000 | 657 | 577 | -12% | 208 ± 29 | 267 ± 32 |
| Market sweep 3 levels (13 commands) | 10 | 4,799 | 4,158 | -13% | 1,883 ± 58 | 2,014 ± 225 |
| Market sweep 3 levels (13 commands) | 1,000 | 4,802 | 4,162 | -13% | 1,905 ± 157 | 2,171 ± 273 |

Speed, interleaved to cancel out drift (`-p depth=1000 -f 3 -wi 3 -w 1 -i 5 -r 1`, order after, before, after,
before):

| Operation | after #1 | before #1 | after #2 | before #2 |
|---|---|---|---|---|
| Place passive, then cancel | 148 ± 5 ns | 179 ± 39 ns | 178 ± 45 ns | 183 ± 32 ns |
| Aggressive fill and refill | 213 ± 12 ns | 323 ± 81 ns | 287 ± 34 ns | 293 ± 82 ns |

### Before and after: order path (G1 default, interleaved)

`PipelineLatency 20000 20 BUSY_SPIN nojournal`, before and after alternated three times:

| Run | GC pauses before / after | Longest pause before / after | p99.9 before / after |
|---|---|---|---|
| 1 | 11 / 11 | 44.4 / 28.3 ms | 40.1 / 33.5 ms |
| 2 | 11 / 10 | 33.8 / 31.5 ms | 29.0 / 4.4 ms |
| 3 | 11 / 11 | 32.3 / 31.2 ms | 5.4 / 4.4 ms |

Reading:
- **Allocation per operation fell 12-21%**, exactly by the index's share: the gain is deterministic (JMH's
  `gc.alloc.rate.norm` has an error of under 1 B/op).
- **Speed did not measurably change.** The sequential run suggested "after" was slower; the interleaved run shows "after"
  equal or faster in every pair. The laptop's drift is larger than the effect.
- **The order path's GC did not change** (10-11 pauses either way). In the pipeline the engine's index was a small part
  of all allocation; JFR on `PipelineLatency` now shows the event records (`OrderAccepted` 17%, `Trade` 14%), the
  benchmark's own commands (18%), client order ID strings (7%) and price-level churn (`PriceLevel`, `TreeMap$Entry`
  and `Long`, about 12%). Removing those means flyweight events written straight into ring slots (SBE-style) and
  pooled price levels: a redesign of the engine's output, not a small change.
- **Lock-free:** matching was already lock-free (one writer thread, Disruptor sequences). The one lock near the
  answer path is `MarketHub`'s monitor in the outbound stage, shared with snapshot readers on purpose (ADR 0009 #5).
  It was not measured as a hotspot at this load; the lock-free replacement (sequenced snapshot plus buffered deltas) is
  part of the scaling design rather than a change for today.
