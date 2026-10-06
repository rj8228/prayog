# 15. Ops page

`http://app.prayog.localhost/ops`, signed in as a user with the `ops` or `admin` role (`ops1`, `admin1`)
([ADR 0017](../adr/0017-ops-page-and-server-backed-panels.md)).

| Task | Do | Expect |
|---|---|---|
| Run a whole day | **Run a day at 300x** | If closed, the market jumps to the next 09:15 open; the bar fills to 15:30 in about 75 s; the clock returns to 1x and the page reports the trades booked |
| Stop early | **Stop: back to real time** | Speed 1x; the day carries on in real time |
| Halt or close | Market control: **Halt** / **Close** | Halt: cancels only; Close: every open order expires |
| Switch scenario | Simulated traders: **calm** / **volatile**, **Pause** | The traders follow within a second; a paused market maker pulls its quotes |
| Check the pipeline after the trade | After the trade panel | Kafka *connected*, lag near 0, ledger *zero-sum: yes*, last snapshot seq |
| Take a snapshot | **Take a snapshot now** | `Snapshot taken at input seq N`; *Last snapshot* shows it within a second |
| Leaderboard | Bottom panel | Accounts by net P&L; names appear once an account has opened its own P&L |

In the trading workspace, the **Review** preset shows the official Positions & P&L, the Blotter (orders, fills,
refusals) and the Leaderboard.
