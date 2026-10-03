# S7 Sequencer pipeline and simulated clock

**Built:**
- **The ring:** an LMAX Disruptor ring in front of the engine. Many producers, one matching thread that assigns input sequence numbers, and ordered downstream stages.
- **The clock:** a simulated clock with a speed multiplier and a ticker thread.
- **The schedule:** the engine opens and closes the market from clock ticks.

[ADR 0006](../adr/0006-sequencer-pipeline-and-clock.md). Explainer with a simulator: [ring-buffer.html](../overview/ring-buffer.html).

## Concepts

- [ ] **Single writer.** Only one thread ever changes the books, so matching needs no locks. Concurrency is handled once, at the entrance (the ring), instead of everywhere.
- [ ] **Ring buffer vs. a queue.** The ring is a fixed array of reusable slots. Producers claim the next sequence with an atomic operation, write and publish; consumers each track their own position with a counter. No allocation and no lock per message. A `LinkedBlockingQueue` allocates a node per message and takes a lock.
- [ ] **Sequencer.** The ring turns "many threads at once" into one official order. Whatever order arrives is fine; what matters is that there *is* exactly one, and that it's recorded.
- [ ] **Input sequence assigned on the matching thread.** If producers numbered commands themselves, two threads could take numbers 5 and 6 but publish them in the opposite order. Numbering at the consumer makes number and processing order identical by construction.
- [ ] **Stages and gating.** The journal stage runs after matching, and the publishers run after the journal. "Respond only after the journal is on disk" is just a dependency between stages.
- [ ] **Back-pressure.** When the ring is full, `trySubmit` says no and the gateway answers "busy". Without that, a slow disk would grow memory without limit until the process died.
- [ ] **Wait strategies.** Blocking (sleep until woken, low CPU), yielding, or busy-spin (burn a core for the lowest latency). A latency-vs-CPU trade-off chosen by config.
- [ ] **Fail safe.** If a handler throws, its thread stops and trading halts. Skipping a command would silently corrupt the history.
- [ ] **Simulated clock.** `simTime = anchor + wallElapsed × multiplier`. Changing speed re-anchors so time never jumps. The wall clock is injected, so tests control time exactly.
- [ ] **Ticks as commands.** The clock thread's readings enter the engine as journaled `ClockTick`s, so a replay sees identical times even if it runs 1,000× faster.
- [ ] **Fixed offset vs. time zone.** IST is +05:30 all year. A fixed offset means sim time → time of day can never change because of a time-zone database update or daylight-saving rule.
- [ ] **Testing concurrency for determinism.** You can't predict the interleaving, so test the *contract* instead: no gaps, per-producer order kept, and replay equality. Run it repeatedly (5 green runs) and plant a bug to prove it can fail.
- [ ] **Shell gotcha.** zsh does not split `$VAR` into words like bash does; `./mvnw $ARGS` passes one big argument. Write the arguments out in full or use an array.

## In an interview

> "Concurrency is solved once, at the entrance: a multi-producer Disruptor ring feeds a single matching thread that numbers each command. The acceptance test hammers it from eight threads, then replays the recorded order through a fresh engine and requires identical events."

> "Time is an input: a clock thread outside the engine turns wall time into sim time and publishes ticks into the ring, so replays never read a real clock."

## Explain-back answers

1. **Why number commands on the matching thread, not when submitted?** Producers race: thread A takes number 5 and thread B number 6, but B publishes first. The consumer-assigned number always equals processing order.
2. **What does back-pressure protect against?** A slow downstream stage (disk, network) would otherwise make producers queue without limit until memory runs out. A bounded ring plus `trySubmit` turns overload into a clear "busy" answer.
3. **Why halt on a handler exception instead of logging and continuing?** Continuing would mean a command was applied without being journaled, or skipped after being numbered. The recorded history would no longer match reality, and replay would diverge.
4. **Why do ticks go through the ring like orders?** Their position relative to orders matters: an order before or after the 15:30 tick is accepted or rejected. Only the ring order, journaled, can reproduce that.
5. **How do you test something whose interleaving you can't control?** Assert properties that must hold for *every* interleaving (no gaps, per-producer order, replay equality), repeat the test, and plant bugs to confirm it can fail.
