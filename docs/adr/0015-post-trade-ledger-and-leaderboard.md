# 15. Post-trade ledger and leaderboard

Date: 2026-10-07

## Status

Accepted

## Context

S16 and S17 turn the exchange's event stream (ADR 0013) into what a trader and the leaderboard need:
- positions, realised and unrealised P&L, simple charges;
- order and fill history for the blotter;
- a ranking by net P&L.

The stream is delivered at least once, can repeat events after an outage, and has no order across partitions.
BUILD_PLAN 16.1 #9, #10 and #25 fixed PostgreSQL with Flyway and jOOQ, migrations as a one-off job, and a Redis
sorted set `prayog:leaderboard`.

## Decisions

1. **One service, `services/post-trade`** (Spring Boot MVC, port 8081). It holds both the ledger (S16) and the
   leaderboard (S17), because the board is a view of the ledger. Traefik routes `/api/v1/account`,
   `/api/v1/leaderboard` and `/api/v1/post-trade` to it on both `api.` and `app.`, so the web app needs no new origin.
2. **jOOQ code generation from the migration files (`DDLDatabase`)**, not `testcontainers-jooq-codegen-maven-plugin`.
   - That plugin targets Testcontainers 1.x and was last released in 2024.
   - `DDLDatabase` (in jOOQ's `jooq-meta-extensions`) parses the Flyway SQL itself, so `./mvnw package` needs no
     Docker. That also makes the image build possible.
   - The SQL stays portable (bigint, varchar), which the parser handles. Generated code is not committed.
3. **Idempotency by natural keys, not only a per-partition watermark.**
   - A trade is inserted with its trade id as primary key. Only if the insert adds a row are its fills, positions,
     charges, order fills and mark applied, all in the same transaction. A redelivered trade changes nothing, in any
     order.
   - A per-partition "last event id" alone is not enough. After a failed send the publisher resends from its
     checkpoint, so in a rare case a partition could see a newer event before an older one it never received. A
     watermark would then skip the older one forever.
   - Order status updates carry the event id and apply only over an older one. Rejections are keyed by event id.
   - Consumer progress per partition is still stored, for lag reporting.
4. **One Kafka batch = one database transaction.** Offsets are committed after it (ack mode BATCH). A failing batch
   (database down) is retried every second forever: Spring Kafka's default gives up and skips, which for a ledger
   means silently losing trades.
5. **Average-cost positions in exact integer paise.**
   - A position is a signed quantity and the signed total cost. Partial closes remove `cost × closed / held`,
     truncated, and the leftover stays with the open position.
   - So `realised − cost` always equals the cash that changed hands, exactly.
   - It matches the browser's estimate method (ADR 0012); the post-trade numbers are now the official ones.
6. **Charges (simple MVP model):** brokerage 0.03% capped at ₹20 per fill, plus 0.0035% fees, rounded half up to the
   paisa. Rates are in parts per million, configurable.
7. **Unrealised P&L at the last trade price** per symbol (`marks`). Net P&L = realised + unrealised − charges.
8. **Leaderboard in Redis.**
   - Sorted set, member = account id, score = net P&L in paise. Doubles are exact up to 2^53, about ₹90 trillion.
   - After each batch commits, the accounts that traded, plus every holder of a symbol whose mark moved, are
     re-scored.
   - PostgreSQL is the truth: on start, or after any Redis error, the board is rebuilt from the ledger and swapped
     in atomically (`RENAME`). Updates and rebuilds run on the listener thread, so a rebuild never overwrites a newer
     update.
9. **Readable names.** Account ids are hashes (ADR 0009). When a signed-in caller asks for their own account,
   post-trade records `username/label` for that id. The board shows names it knows and ids otherwise. The
   account-id hash moved to `contracts` (`AccountIds`), so the exchange and post-trade agree by construction.
10. **Zero-sum check.** A market is closed: every share bought was sold by someone. At one mark per symbol, net
    quantity per symbol and P&L before charges summed over all accounts are exactly 0.
    `GET /api/v1/post-trade/status` (ops) reports both, and the tests and `make e2e` require 0.
11. **Migrations run as a one-off job** (`post-trade-migrate`) from the post-trade image itself (`Migrate` main
    class), so the job and the tests use the same Flyway version and no extra image is pulled.

## Testing

- Unit: average cost (averaging, partial close, shorts, through zero, odd paise), with a property test: for any fill
  sequence, `realised − cost = cash` and `realised + unrealised = cash + quantity × mark`. Charges: percentage, cap,
  half-up rounding.
- `LedgerIT` (Testcontainers PostgreSQL, real migrations): **P&L reconciles exactly with the trade log.** 3,000 random
  trades among 8 accounts are compared, per account, with a naive computation from the trades (cash plus open
  quantity at the mark, charges per fill). Then the whole log is redelivered shuffled, and the positions and orders
  tables must be unchanged. Also the blotter lifecycle, and a late older event that must not undo a newer one.
- `PostTradeIT` (Kafka, PostgreSQL and Redis containers): events on the topic become positions, P&L, fills, orders and
  a leaderboard over the API; redelivering the topic changes nothing; a batch is one transaction (a bad event rolls
  back the good one before it); access rules.
- `make e2e` → `tests/e2e/posttrade_check.py`: consumed up to the exchange's published seq; zero-sum on the live
  market; a bot's market orders appear as fills, P&L, charges and leaderboard ranks; post-trade stopped
  mid-session, a trade made while it was down, then caught up and still zero-sum.
- Planted bug caught: dropping the "trade already seen" guard (redelivery doubled positions).

## Trade-offs

- The `orders` table grows with every order (most from the simulated traders); retention comes with segment
  archiving (M4 or later).
- If events of one order arrive out of order after an outage, the blotter's status for that order can lag. Positions
  and P&L cannot, because trades are applied by key.
- One consumer instance. Partitions allow more later, but the ledger transaction locks one position row at a time,
  which is enough at this scale.
