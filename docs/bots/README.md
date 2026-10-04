# Building a trading bot for Prayog

Prayog is a bot arena: anything that speaks HTTP and WebSocket can trade on it. This guide takes you from nothing
to a running strategy, then covers what separates a bot that works from one that quietly loses track of its own
orders. The full endpoint list is in [api.md](api.md).

## 1. What a bot is here

A bot is an ordinary API client with its own **account**. It:

1. authenticates as a Keycloak **client** (OAuth2 client credentials: a client id and secret, its "API key");
2. reads the market from the **market-data WebSocket** (the public order book and trades);
3. learns about its own orders from the **private WebSocket** (acceptances, cancels, fills);
4. sends orders over **REST**; each answer comes back only after the exchange has written the order to its journal.

The simulated traders in `services/agents/` are bots built exactly this way; read them as worked examples.

## 2. Credentials

Locally there is a ready-made bot client, `prayog-bot-demo`. Its secret is in your `.env`:

```sh
grep PRAYOG_BOT_DEMO_SECRET .env
```

The Python SDK and the `prayog` CLI pick it up automatically when run from the repository root. Other languages
fetch a token like this (tokens last 5 minutes; fetch a new one before they expire):

```sh
curl -s -d grant_type=client_credentials -d client_id=prayog-bot-demo -d client_secret="$SECRET" \
  http://auth.prayog.localhost/realms/prayog/protocol/openid-connect/token
```

**Several strategies, one client.** Send the header `X-Prayog-Account: <label>` (lowercase letters, digits and
`-`, up to 32 characters). Each label is a separate account with its own orders, positions and rate limit:
`mm`, `momentum-1`, `test`. Without the header you trade as `main`. A label can only ever reach accounts under
your own client.

## 3. A first bot in Python

```python
import asyncio
from prayog_sdk import LocalBook, PrayogClient, Settings, market_data


async def main():
    settings = Settings.from_env(account="first-bot")
    async with PrayogClient(settings) as client:
        book = LocalBook("INFY")
        async for message in market_data(settings, ["INFY"]):
            book.apply(message)  # snapshot, then every numbered change
            if message["type"] == "trade" and book.best_bid:
                print("last", message["price"], "bid", book.best_bid, "ask", book.best_ask)
                if message["price"] < 148_000:  # prices are integer paise: 1480.00 rupees
                    result = await client.buy_limit("INFY", quantity=5, price=book.best_bid)
                    print(result.status, result.order_id)


asyncio.run(main())
```

Run it with `uv run python first_bot.py` from the repository root. A fuller example with position tracking and
risk limits: [`sdk/python/examples/sample_bot.py`](https://github.com/rj8228/prayog/blob/main/sdk/python/examples/sample_bot.py)
(`uv run python sdk/python/examples/sample_bot.py --symbol INFY`).

## 4. Money and time

- **Prices are integer paise.** ₹1,495.50 is `149550`. Never use floating point for prices; the SDK has
  `rupees("1495.50") -> 149550` and `to_rupees(149550) -> "1495.50"`.
- **Ticks and bands.** Every symbol has a tick size (₹0.05 = `5`) and a band of ±10% around its reference price;
  `GET /api/v1/instruments` lists them. Orders off the tick or outside the band are rejected.
- **Sim time.** Times are simulated exchange time in epoch microseconds. Trading hours are 09:15 to 15:30 IST;
  outside them orders are rejected with `SESSION_NOT_OPEN` and every open order expires at the close.

## 5. Market data: keep an exact local book

`ws://api.prayog.localhost/api/v1/ws/market?symbols=INFY,TCS&depth=20` sends:

1. one `snapshot` per symbol (book levels, recent trades, ticker) with a `seq`;
2. then `trade` and `book` messages per symbol, each with `seq` exactly one higher than the previous one for that
   symbol; a `book` message lists the price levels that changed (quantity 0 means the level is gone);
3. `session` messages when the market opens, halts or closes, and a `heartbeat` every 5 seconds.

Apply every message in order. **If a `seq` is not the previous one plus one, you missed something**: reconnect for a
fresh snapshot rather than trading on a wrong book. The SDK's `LocalBook` raises `SequenceGap` and its stream
reconnects for you. The end-to-end test rebuilds books this way and checks they equal the exchange's own.

## 6. Your orders: trust the exchange, not your memory

- Every REST answer says what the exchange did: `resting`, `filled`, `cancelled` (nothing left open), `modified` or
  `rejected` with a `reason`. Fills that happened at once are in `fills`.
- Resting orders fill later, when someone trades against them. Those fills arrive on the **private feed**
  (`/api/v1/ws/private`, same token, same account label as a query parameter `?account=`).
- **A fill can arrive on the private feed before the REST answer that told you the order id.** The exchange sends
  both at the same moment; the network decides which you see first. A bot that ignores fills for "unknown" order
  ids will believe it still has orders it doesn't. Prayog's own market maker had exactly this bug.
- So **reconcile**: every few seconds, read `GET /api/v1/orders` (your open orders) and treat it as the truth: drop
  orders that are gone, take filled quantities from it, cancel any order you have lost track of.
- After a private-feed reconnect, reconcile immediately: updates sent while you were disconnected are not replayed.

## 7. Rules that will bite you

| Rule | What happens | What to do |
|---|---|---|
| Self-trade prevention | If your incoming order would trade with your own resting order, the incoming order is cancelled (`SELF_TRADE_PREVENTION`) | When moving quotes, move the side stepping **away** from the market first (asks up before bids up) |
| Modify loses priority | Changing price, or raising quantity, sends the order to the back of the queue; only a same-price reduction keeps its place | Don't churn orders you want filled |
| Modify quantity is the new **total** | Including what already filled (as in FIX). A total at or below the filled amount cancels the rest | Track filled quantity per order |
| Market orders never rest | Whatever can't fill now is cancelled (`NO_LIQUIDITY`) | Check `filledQuantity` |
| Day orders | Everything open expires at 15:30 | Re-quote after the next open; watch `session` messages |
| Rate limits | Bots: 500 requests a second per account, bursts of 1,000; more gets `429` | Back off; `RateLimitedError` in the SDK |
| Busy | `503` means the exchange's queue is full | Retry after a short pause |
| Kill switch | Ops can disable your account: your orders are cancelled and new ones rejected (`ACCOUNT_DISABLED`) | Stop and investigate |

Your bot can also cancel everything it has with `DELETE /api/v1/orders` (`client.cancel_all()`): do it on start-up
and shutdown.

## 8. Strategy ideas, in rising difficulty

1. **Taker on a signal**: buy below a threshold, sell above (the sample bot).
2. **Momentum**: compare a fast and a slow moving average of trade prices
   ([`services/agents/src/prayog_agents/quoting.py`](https://github.com/rj8228/prayog/blob/main/services/agents/src/prayog_agents/quoting.py)).
3. **Market maker**: quote both sides around your estimate of fair value; skew quotes against your inventory so it
   mean-reverts; cap the inventory ([`agents.py`](https://github.com/rj8228/prayog/blob/main/services/agents/src/prayog_agents/agents.py), class
   `MarketMaker`). Background: `open docs/overview/market-making.html`.
4. **Pairs or cross-symbol**: trade one symbol on another's moves.

Before writing your own, try the six strategies in the web app's **Strategies** panel (TWAP, iceberg, grid, mean
reversion, momentum, market maker; [user guide](../guides/using-the-market.md)). Each is a pure `decide(context)`
function returning orders and cancels, plus a runner that applies risk checks, in
[`apps/web/src/strategies`](https://github.com/rj8228/prayog/tree/main/apps/web/src/strategies). The same shape
works for a Python bot: keep the decision pure and testable, and put risk checks and I/O around it.

The simulated market's fair value is a mean-reverting random walk with jumps (`AGENTS_SCENARIO=calm` or
`volatile` in `.env`, then `make up`). Mean reversion is a real edge here; momentum works in bursts.

## 9. Testing and debugging your bot

- Run your bot under its own account label so its orders never mix with your manual trading.
- `uv run prayog --account <label> orders` shows what the exchange thinks your bot has open.
- `make logs SERVICE=exchange` shows rejected orders with their reason and your account id (JSON lines; pipe through
  `jq` to filter).
- `http://api.prayog.localhost/actuator/prometheus` has order counts by outcome (`prayog_orders_total`): a sudden
  rise in `cancelled` or `rejected` usually means a logic bug.
- `make e2e` checks the whole market is healthy: if it passes and your bot misbehaves, the bug is in the bot.

## 10. Other languages

There is no magic in the SDK: it is about 400 lines over httpx and websockets. The wire protocol is plain JSON over
HTTP and WebSocket; [api.md](api.md) has every endpoint and message. Remember to send `Authorization: Bearer ...`
and, for the private WebSocket, either that header or `?access_token=...`.
