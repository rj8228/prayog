# 17. Ops page and server-backed workspace panels

Date: 2026-10-07

## Status

Accepted

## Context

S19 needs an ops page from which a full simulated day can be run, plus a blotter, a P&L panel and a leaderboard
view. The rest of S18 asks for server-backed blotter and P&L panels in the Step 1.5 workspace. The admin console
(ADR 0011) already had market and simulation controls, but only for the `admin` role. The workspace's positions were
a browser estimate (ADR 0012).

## Decisions

1. **A separate `/ops` page for `ops` or `admin`.** It reuses the admin console's market and simulation panels, now
   driven by plain props (status, simulation) instead of the admin-only overview. It adds:
   - **Run a simulated day:** if the market is closed, jump to the next open; set the clock to 300×; show progress
     from 09:15 to 15:30 in sim time; once the close has passed, put the clock back to real time and report the
     wall time and the trades post-trade booked. A day takes about 75 seconds.
   - **After the trade:** Kafka publisher state and lag, ledger progress and the zero-sum check, trades, accounts and
     charges, the last snapshot and whether this run started from one, and a "take a snapshot now" button.
   - **The leaderboard.**
2. **`ops` may read and change the simulation** (`GET /api/v1/simulation`, `PUT /api/v1/admin/simulation`). Steering
   the simulated traders is running the market. The rest of `/api/v1/admin/**` stays admin-only, which is tested.
3. **Official numbers in the workspace.** The Positions panel shows the post-trade ledger (every fill ever, average
   cost, charges, rank). It falls back to the browser estimate only when post-trade does not answer. New panels:
   **Blotter** (orders, fills with charges and realised P&L, refused orders) and **Leaderboard**. A new **Review**
   preset puts them together.
4. **Polling, not a new socket.** The ledger lags the exchange by milliseconds and changes a few times a second, so
   panels poll every 2-3 s. Authenticated polls stop when signed out, so no silent sign-in is attempted for visitors.

## Testing

- Unit (web): day progress before, during and after trading hours in IST; wall seconds per day at a speed; presets
  fit the grid without overlap, including the new one.
- API (exchange): ops can read and change the simulation; a trader cannot; ops still gets 403 on the admin overview.
- Browser tests of these pages come with the UI test work at the end of the project (handoff).

## Trade-offs

- "Run a day" is driven from the browser tab: closing the tab mid-day leaves the clock fast. The page shows the
  speed, and "Real time" puts it back.
- The blotter shows the main account; strategy accounts keep their own panel (ADR 0012).
