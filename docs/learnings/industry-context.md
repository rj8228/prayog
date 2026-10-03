# Industry context

Background that doesn't belong to one session. Longer versions are in the [functional](../overview/functional.html) and [technical](../overview/technical.html) overviews.

## Exchanges and trading firms

- [ ] **Two organisations, one cable.** The *exchange* (NSE, Nasdaq) runs the market and keeps the one official order book. A *trading firm* (an HFT firm, a fund, my paper-trading algo) is a customer: it reads the exchange's feed, decides and sends orders.
- [ ] **Two kinds of order book.** The exchange's book is the real one. A trading firm rebuilds its own copy from the market data feed (a "book builder") so its strategy can read it locally.
- [ ] **HFT firm pipeline.** Feed handler (ingestion) → book builder → strategy → pre-trade risk checks → order management (OMS/EMS) → order gateway. Around it sit research and backtesting, a tick data store, monitoring, P&L reporting, FPGAs and colocation.
- [ ] **FPGA.** A chip programmed as a circuit rather than software. HFT firms use FPGAs to decode feeds and send orders in well under a microsecond. The software counterparts are no allocation, busy-spinning and one thread per core.
- [ ] **Prayog's scope.** The full exchange, a thin broker layer (positions, P&L, charges) and a starter kit for the trading-firm side (the SDK). HFT hardware is out of scope.

## What production matching engines do differently

- [ ] **Price levels as an array.** Slot = (price − lowest allowed price) ÷ tick, plus a bitmap of non-empty slots. Price bands make the range small (₹100 ±20% with a 5-paise tick is 800 slots), so every lookup is one array access. exchange-core uses an adaptive radix tree instead.
- [ ] **Primitive-keyed maps** (Agrona `Long2ObjectHashMap`) avoid boxing `long` keys.
- [ ] **Object pools and flyweights:** objects are allocated once and reused, so there is no garbage and no GC pauses on the matching thread.
- [ ] **Replicated state machine.** A deterministic engine fed the same sequenced inputs stays identical, so a standby copy can take over instantly (LMAX; Aeron Cluster with Raft). Prayog's determinism keeps this door open.
- [ ] **"Measure, don't guess."** Prayog keeps the textbook structures and switches only when S9 benchmarks show a need.

## Sources

- Martin Fowler, "The LMAX Architecture" (martinfowler.com/articles/lmax.html)
- WK Selph, "How to Build a Fast Limit Order Book" (2011)
- exchange-core (github.com/exchange-core/exchange-core): compare `OrderBookNaiveImpl` with `OrderBookDirectImpl`
