# S9 Benchmarks

**Built:** JMH microbenchmarks of the matching engine (`make bench`), an end-to-end latency harness for the order
path with HdrHistogram (`make bench-latency`), and the first entries in [benchmarks.md](../benchmarks.md).

## Concepts

- [ ] **JMH.** Handles warm-up (JIT), forks a fresh JVM, and stops dead-code elimination with a Blackhole. Hand-written
  `System.nanoTime()` loops get all three wrong.
- [ ] **Steady state.** Each benchmark undoes its own effect (place then cancel; take then refill) so the book does not
  grow during the run, and the number means the same thing at the end as at the start.
- [ ] **Percentiles, not averages.** Latency is a distribution; the p99 and the max are what a user notices.
- [ ] **Coordinated omission.** Measure from when a request *should* have been sent. If the system stalls, requests
  queue behind the stall; timing only the ones actually sent hides that wait.
- [ ] **Group commit.** One fsync per batch: throughput scales with load while latency stays at one fsync.
- [ ] **GC tails.** G1 stops the application for milliseconds; ZGC works concurrently. Allocation on the hot path is
  what feeds the collector.

## Explain-back: questions and model answers

### 1. The engine does a command in about 100 ns, yet an order takes 12 ms end to end. Where do the other 11.9 ms go?

**Answer:** Almost all to `fsync`. The journal stage makes each batch durable before any later stage may answer the
client (ADR 0006, 0007), and an fsync on this Mac's SSD takes milliseconds. The same run without the journal has a
median of 0.5-24 µs. The design choice is deliberate: never confirm something a crash could erase. A production
venue gets the same guarantee faster with drives that acknowledge writes from a power-protected cache, or by
replicating the journal to another machine and confirming once a replica has it (no local fsync on the path).

### 2. Why did the 1,000/s and 20,000/s runs have nearly the same median?

**Answer:** Group commit. The journal flushes once per Disruptor batch. At 1,000/s most batches hold one command, so
each waits a full fsync. At 20,000/s the ring fills while one fsync runs, the next batch holds hundreds of commands,
and one fsync covers them all. Latency stays at roughly one fsync while throughput grows 20 times. The cost shows in
the tail at 20,000/s (p99 141 ms), when a slow fsync makes a large batch wait.

### 3. What is coordinated omission, and how does the harness avoid it?

**Background:** A naive load test sends a request, waits for the answer, then sends the next. If the system freezes
for 100 ms, the test also freezes and simply sends nothing, so only one request records the 100 ms.

**Answer:** `PipelineLatency` schedules command *i* for `start + i × interval` and records `answered − intended`. If
the pipeline stalls for 100 ms at 20,000/s, the 2,000 commands that should have gone out in that time each record
their share of the wait, which is what real clients sending at that rate would see. A harness that measured from the
actual send would report a p99 orders of magnitude better than the truth.

### 4. Without the journal the p99 was still 13 ms. How did we find the cause, and what fixed it?

**Answer:** `-Xlog:gc` showed 10 G1 pauses of up to 26 ms in a 20-second run. Every pause stops all threads, so the
commands in flight during it (and those intended during it) record the pause. Running the same test with ZGC, whose
pauses are under a millisecond, brought p99 from 13.5 ms to 1.3 ms, and p90 from 50 µs to 2 µs. The lasting fix is to
allocate less on the order path: the study guide's step 5 (pooled events, primitive collections).

### 5. Why does book depth barely change the engine's numbers?

**Answer:** Prices are kept in a `TreeMap`, so finding the best level is O(log n): ten levels or a thousand is a few
extra comparisons. Within a level, orders are an intrusive doubly linked list with an id → order map, so a cancel
unlinks one node without walking the queue. What does cost more is how many orders a command touches: the market
order that fills 12 orders takes about 10× the time of a single fill.

## In an interview

"I measure the engine with JMH and the order path with an open-loop harness that records from the intended send time
into an HdrHistogram. The engine does about 100 ns a command; end to end is dominated by fsync, because nothing is
acknowledged before it is durable, and group commit keeps throughput up. GC logs explained the tail, and ZGC cut p99
tenfold."
