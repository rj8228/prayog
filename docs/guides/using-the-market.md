# Using the market

How to open Prayog's live market, watch it move and trade in it yourself. For bots, see
[docs/bots/](../bots/README.md).

## 1. Start everything (once per boot)

Docker Desktop must be running. From the repository root:

```sh
make env    # first time only: creates .env with your local passwords and secrets
make up     # builds what changed and starts everything; returns when all services are healthy
make smoke  # optional: 28 quick checks that everything works
```

The first `make up` takes a few minutes (images download and build). Later starts take 20 to 40 seconds.

What starts: the exchange, a market maker, noise and momentum traders, the web app, plus PostgreSQL, Kafka, Redis,
Keycloak and Traefik underneath.

## 2. Watch the market

Open **http://app.prayog.localhost** in Chrome or Firefox. No login is needed to watch.

| Part of the page | What it shows |
|---|---|
| Top bar | Session (OPEN, HALTED or CLOSED), the simulated clock in IST, and a green dot while the live feed is connected |
| Ticker row | Each symbol's last price and change against its reference price. Click one to select it |
| Price chart | Candles of the selected symbol; switch between 10-second, 1-minute and 5-minute candles |
| Order book | The best 10 price levels each side: sell orders (red) above, buy orders (green) below, the spread between |
| Trades | Every trade as it happens: time, price, quantity; green when the buyer took liquidity, red when the seller did |

The simulated traders keep the market busy: the market maker always quotes both sides around a hidden "fair
value" that drifts and occasionally jumps, and other traders buy and sell against it.

## 3. Sign in

Click **Sign in to trade**. You land on Keycloak's login page. Use one of the seeded users:

| User | Can | Password |
|---|---|---|
| `trader1` | Trade | `grep PRAYOG_TRADER1_PASSWORD .env` |
| `ops1` | Market operations (no trading) | `grep PRAYOG_OPS1_PASSWORD .env` |

After signing in you return to the market with the order ticket enabled. Your session lasts while the tab is open;
tokens renew themselves every few minutes.

## 4. Trade

1. Pick a symbol in the ticker row.
2. In **Place an order** choose **Buy** or **Sell**, then **Limit** or **Market**.
3. Enter a quantity. For a limit order enter a price in rupees, or click a price in the order book to copy it.
4. Press **Buy INFY** (or **Sell ...**). The answer appears under the button:

| Answer | Meaning |
|---|---|
| `resting, 15 open` | Your limit order is on the book, waiting. It shows in the order book and in **My orders** |
| `filled 10 @ 1,509.10` | It traded at once (a market order, or a limit order that crossed the spread) |
| `resting, filled 4 @ ..., 6 open` | Part traded, the rest waits on the book |
| `cancelled (NO_LIQUIDITY), filled 3 @ ...` | A market order took what was available; the rest is cancelled (market orders never wait) |
| `rejected (PRICE_OUTSIDE_BAND)` | Refused: price outside today's ±10% band. Other reasons: `PRICE_NOT_ON_TICK` (prices move in ₹0.05 steps), `SESSION_NOT_OPEN`, `INVALID_QUANTITY` |

**My orders** lists your open orders with a **Cancel** button (and **Cancel all**). **My fills** shows each of your
trades the moment it happens, including resting orders that fill later.

## 5. Rules worth knowing

- Prices move in ticks of ₹0.05, within ±10% of each symbol's reference price.
- Orders are filled by price, then time: better prices first; at the same price, earlier orders first.
- Your own buy and sell orders never trade with each other (self-trade prevention cancels the incoming one).
- Every order is a day order: anything still open at the 15:30 close expires. With the default settings the clock
  then jumps to the next day's 09:15 open about 30 seconds later.
- The simulated clock runs at real speed by default (`SIM_CLOCK_MULTIPLIER=1` in `.env`).

## 6. Trade from a terminal instead

The same exchange, from the repository root (it trades as the demo bot account):

```sh
uv run prayog tickers
uv run prayog book INFY
uv run prayog buy INFY 10 --price 1495.50
uv run prayog sell INFY 10          # market order
uv run prayog orders
uv run prayog cancel-all
uv run prayog watch INFY            # live order book in the terminal (Ctrl-C to stop)
```

## 7. Stop

```sh
make down    # stops everything, keeps the market's history
make reset   # stops everything and deletes all local data (a brand-new market next time)
```

If something looks wrong, see the [runbook's debugging page](../runbook/07-debugging.md).
