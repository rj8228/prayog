# 11. Admin console: admin user, self-test, live simulation control, journal views

Date: 2026-10-05

## Status

Accepted

## Context

Step 1.5 adds an admin view: a user who trades like anyone and can also test the system with a click, steer the
simulated market, manage accounts, and look back through the journal. The market's data must survive adding the user.

## Decisions

1. **Role `admin`, user `admin1` with roles `trader`, `ops`, `admin`.** In the realm file for fresh installs; for
   existing installs `deploy/compose/users.sh` (run by `make up`, also `make users`) creates the role and user through
   Keycloak's admin API, idempotently, and keeps the password in step with `.env`. `make up` first adds any variable
   from `.env.example` missing in `.env` (`make env-upgrade`).
2. **Access:** `/api/v1/admin/**` needs `admin`; ops endpoints accept `ops` or `admin`; `GET /api/v1/simulation` is
   readable by `bot` (the simulated traders) and `admin`.
3. **Self-test (`POST /admin/selftest`)**, six live checks, each reported even if another fails: an order round trip on
   a dedicated self-test account; no crossed book; ring room; no publish errors; trades happening; an **online
   replay**: replay every readable input record and compare events with the event log up to the last seq both reached
   (`Replay.checkOnline`), so the live journal is verified without stopping the exchange.
4. **Simulation control:** the admin sets scenario, pause and one-off "news" jumps in the exchange
   (`SimulationControl`, in memory, versioned; jumps have ids). The traders poll it every second and apply each change
   once; jumps made before a trader started are ignored. A paused market maker cancels its quotes.
5. **Account directory:** who each hashed account id belongs to (username, label, client), learned from requests; in
   memory. The console lists accounts with open orders, requests and rejects, and offers cancel-all and the kill switch.
6. **Journal views, read from disk while running:** an order's journey (every event mentioning it, owner or admin
   only) and a replay window for one symbol (the input journal replayed through a fresh engine so command boundaries
   are exact; a snapshot plus up to 20,000 frames).
7. **Web:** `/admin` for admins (header tab), polling overview and accounts; journey dialog for traders' own orders
   with the round trip measured in the browser.

## Testing

- API tests: admin-only endpoints (trader and ops get 403), the simulation feed readable by bots, an admin can trade,
  self-test passes, simulation changes and validation, journeys visible to the owner and admins only, account
  directory, replay window. Planted bugs (traders on admin endpoints, journeys for anyone) caught.
- Core: `checkOnline` matches when the event log lags and catches a different engine.
- Agents: jumps apply once, old jumps ignored, scenario and pause switch live, jumps stay inside the band.
- Browser: as `admin1`, self-test 6/6 on the live market (online replay of ~286k commands), a +3% news jump moved INFY
  about 3% within seconds, pause and resume, replay viewer playback.

## Trade-offs

- Simulation control and the account directory are in memory: a restart resets them (the journal is untouched).
- The online replay and journey scan read the whole journal on each request; fine at this size, revisit with
  segment archiving.
- `users.sh` uses the Keycloak admin password from `.env`; it is a local-development tool.
