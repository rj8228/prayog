# API reference

Base URL for bots: `http://api.prayog.localhost` (the web app uses `/api` on its own host). All bodies are JSON.
Prices are integer **paise**; times are sim time in **epoch microseconds**. Authenticated calls need
`Authorization: Bearer <token>`; `X-Prayog-Account: <label>` picks the account (default `main`).

## Public (no token)

| Method and path | Returns |
|---|---|
| `GET /api/v1/instruments` | `[{symbol, tickSize, maxOrderQuantity, referencePrice, bandPercent, bandLow, bandHigh}]` |
| `GET /api/v1/session` | `{state, simTime, clockMultiplier, open, close, offset}` |
| `GET /api/v1/market/tickers` | `[{symbol, referencePrice, last, open, high, low, volume, trades, bestBid, bestAsk}]` |
| `GET /api/v1/market/{symbol}/book?depth=20` | The same `snapshot` message the WebSocket sends |
| `GET /api/v1/market/{symbol}/trades?limit=200` | Recent `trade` messages, oldest first (up to 5,000) |
| `GET /actuator/health/readiness` | `{"status":"UP"}` when the exchange is ready |
| `GET /actuator/prometheus` | Metrics: `prayog_orders_total{kind,outcome}`, `prayog_order_latency_seconds`, `prayog_ring_remaining`, ... |

## Trading (role `trader` or `bot`)

| Method and path | Body | Returns |
|---|---|---|
| `GET /api/v1/me` | | `{subject, accountLabel, accountId, clientId, roles}` |
| `POST /api/v1/orders` | `{symbol, side: BUY\|SELL, type: LIMIT\|MARKET, price (LIMIT only), quantity, clientOrderId?}` | `201` + OrderResult |
| `DELETE /api/v1/orders/{orderId}` | | OrderResult (`cancelled` or `rejected`) |
| `PATCH /api/v1/orders/{orderId}` | `{price, quantity (new TOTAL), clientOrderId?}` | OrderResult (`modified`, `cancelled` or `rejected`) |
| `GET /api/v1/orders` | | Open orders: `[{orderId, clientOrderId, symbol, side, price, quantity, leavesQuantity, filledQuantity}]` |
| `DELETE /api/v1/orders` | | `{requested, cancelled}`: cancels all your open orders |

**OrderResult**: `{status, orderId, clientOrderId, symbol, reason, filledQuantity, leavesQuantity, fills:
[{tradeId, price, quantity}], inputSeq, simTime}`.

| `status` | Meaning |
|---|---|
| `resting` | On the book with `leavesQuantity` open (maybe partly filled already) |
| `filled` | Completely filled now |
| `cancelled` | Nothing left open; `reason` says why (`NO_LIQUIDITY`, `SELF_TRADE_PREVENTION`, `CLIENT_REQUEST`, `MODIFIED_TO_ZERO`) |
| `modified` | The modify was applied (it may also have traded: see `fills`) |
| `rejected` | Refused; `reason`: `UNKNOWN_SYMBOL`, `INVALID_PRICE`, `INVALID_QUANTITY`, `PRICE_NOT_ON_TICK`, `PRICE_OUTSIDE_BAND`, `SESSION_NOT_OPEN`, `UNKNOWN_ORDER`, `DUPLICATE_CLIENT_ORDER_ID`, `ACCOUNT_DISABLED` |

## Errors

`{ "error": "...", "message": "..." }` with: `400 bad_request` (missing or invalid field), `401` (no or bad token:
`WWW-Authenticate` says why), `403` (wrong role), `404 not_found` (unknown symbol, or no open order with that id for
this account), `429 rate_limited`, `503 busy` (exchange queue full; retry).

## Market-data WebSocket (public)

`ws://api.prayog.localhost/api/v1/ws/market?symbols=INFY,TCS&depth=20` (all symbols if `symbols` is omitted)

| `type` | Fields |
|---|---|
| `snapshot` | `symbol, seq, session, bids: [{price, quantity, orders}], asks: [...], trades: [trade...], ticker` |
| `book` | `symbol, seq, changes: [{side, price, quantity, orders}]` (quantity 0 removes the level) |
| `trade` | `symbol, seq, tradeId, price, quantity, aggressor: BUY\|SELL, simTime` |
| `session` | `state: OPEN\|HALTED\|CLOSED, simTime` |
| `heartbeat` | `simTime` (every 5 s) |

`seq` is per symbol and increases by exactly 1 per `book` or `trade` message after the snapshot.

## Private WebSocket (role `trader` or `bot`)

`ws://api.prayog.localhost/api/v1/ws/private?account=<label>` with the bearer token in the `Authorization` header or
as `&access_token=...`.

| `type` | Fields |
|---|---|
| `hello` | `accountId, accountLabel` (first message) |
| `order` | `status: accepted\|rejected\|cancelled\|modified, eventSeq, simTime, orderId, clientOrderId?, symbol, side?, orderType?, price?, quantity?, leavesQuantity?, reason?` |
| `fill` | `eventSeq, simTime, tradeId, orderId, symbol, side (yours), price, quantity, aggressor (true if your order took liquidity)` |
| `heartbeat` | `simTime` |

Updates sent while you were disconnected are not replayed: reconcile with `GET /api/v1/orders` after reconnecting.

## Operations (role `ops`)

| Method and path | Body | Effect |
|---|---|---|
| `GET /api/v1/ops/status` | | Recovery info, last input seq, ring capacity and free slots, publish errors, sim time, session, Kafka publisher (`kafka`: enabled, connected, publishedSeq, lag, errors) |
| `POST /api/v1/ops/session` | `{state: OPEN\|HALTED\|CLOSED}` | Global kill switch; HALTED allows cancels only; CLOSED expires all orders |
| `POST /api/v1/ops/accounts/{accountId}` | `{enabled: false}` | Per-account kill switch: cancels its orders, refuses new ones |
| `PUT /api/v1/ops/clock` | `{multiplier: 1..10000}` | Sim speed |
| `POST /api/v1/ops/clock/next-open` | | Jump to the next day's open |

## Event stream (Kafka)

Every exchange event, in the shape of `contracts/schemas/events/exchange-event.schema.json`, on topic
`prayog.exchange.events.v1` (3 partitions). From containers use `kafka:9092`, from the host `localhost:9094`.

- **Key:** the symbol, so one symbol's events are in order on one partition. Session-wide events
  (`SessionStateChanged`) use the empty key.
- **Event id:** `seq`, also in the `eventId` header. Delivery is at least once: skip any id you have already applied.
- **Order:** by `seq` within a partition; across partitions, sort by `seq` if you need it.
