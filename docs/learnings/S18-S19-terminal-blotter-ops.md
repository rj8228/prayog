# S18–S19 Trading terminal, blotter, P&L and ops page

**Built:** an `/ops` page that runs a full simulated day (about 75 s at 300x) and shows everything after the trade;
server-backed Blotter, P&L and Leaderboard panels in the workspace, with a Review preset
([ADR 0017](../adr/0017-ops-page-and-server-backed-panels.md)). Checked in the browser on 2026-10-07: a day ran in 16 s
of wall time and post-trade booked its trades (docs/media/04-ops-run-a-day.png).

## Concepts

- [ ] **Official numbers vs estimates.** The browser can estimate P&L from its own fills; the ledger is the official
  record (every fill ever, charges, average cost). Show the official one and fall back to the estimate, labelled.
- [ ] **Polling vs push.** Data that changes a few times a second and tolerates a 2-3 s delay is simpler to poll than
  to stream; streams are for data whose order and latency matter (market data, own orders).
- [ ] **Roles by job, not by screen.** `ops` runs the market (session, clock, scenarios); `admin` also manages
  accounts and replay. Each API path is guarded and tested for both the allowed and refused role.
- [ ] **Time compression.** A simulated clock at 300x runs a 6 h 15 min day in about 75 s; everything downstream must
  work in sim time (sessions, expiry, charts), never wall time.

## Explain-back: questions and model answers

### 1. Why does the Positions panel prefer the post-trade ledger over the browser's own calculation?

**Answer:** The browser only knows fills it saw while the tab was open, and computes P&L without the official charges.
The ledger consumes every event from Kafka, applies charges, and reconciles to zero across accounts. When post-trade
is down the panel falls back to the estimate so the trader is not blind, and says so.

### 2. Why poll the ledger every 2-3 s instead of adding a WebSocket?

**Answer:** The ledger is already a few milliseconds behind the exchange and changes a few times a second; a human
cannot act on sub-second P&L changes. Polling reuses the existing REST endpoints, auth and caching, and stops when the
user signs out. A socket would add connection management and reconnect logic for no visible benefit.

### 3. "Run a simulated day" is driven from the browser tab. What breaks if the tab closes mid-day, and why accept it?

**Answer:** The clock stays at 300x because the page is what sets it back to real time at the close. The page shows
the speed and has a "Real time" button, and the exchange keeps working correctly at any speed. Moving the routine into
the server would mean a server-side job with its own state for a demo convenience; not worth it for the MVP.

### 4. Why may `ops` change the simulation but not use the rest of `/api/v1/admin/**`?

**Answer:** Steering the simulated traders (scenario, pause, news) is part of running the market, which is the ops
job. Accounts, event replay and the order journey expose every participant's data and are administration. The split
is tested in both directions: ops can change the simulation, a trader cannot, and ops still gets 403 on the admin
overview.

### 5. What does the ops page's "Ledger zero-sum: yes" prove?

**Answer:** In a closed market every trade has a buyer and a seller, so realised plus unrealised P&L before charges
sums to zero across all accounts, and net quantity per symbol is zero. If post-trade skipped, double-counted or
misordered an event, the sums would drift. It is a cheap end-to-end invariant over the whole Kafka → ledger path.
