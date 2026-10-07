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
