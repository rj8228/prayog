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

### Arrange your workspace

Every box on the page is a panel you can move and size:

- **Move:** drag a panel by its title. Other panels make room.
- **Resize:** drag its bottom-right corner.
- **Swap two panels:** the **⇄** menu at a panel's top right; they trade places and sizes.
- **Add or remove:** **+ Add panel** in the toolbar (watchlist, depth chart, positions & P&L, my fills,
  strategies, ...) and **×** on a panel.
- **Presets:** **Trader**, **Scalper** (tall order book for one-click trading), **Watcher** (no trading) and
  **Quant** (strategies). Your own arrangement is kept as **Custom (saved)**, per user, in this browser.
- **Lock** stops accidental drags. **Explain** puts a short note on every panel saying what it shows and how it
  works, with a link to the matching docs page.
- **Shortcuts** (press `?`): `B` / `S` buy or sell (jumps to quantity), `[` `]` previous or next symbol, `X` cancel
  all your orders, `E` explain mode, `L` lock.

On a phone the panels stack in one column.

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
trades the moment it happens, including resting orders that fill later; a notification pops up at the bottom right
too. Click an order id or a fill to see the order's journey through the exchange.

**Positions & P&L** adds up your fills per symbol (average-cost method): shares held, average price, realized P&L
from shares you closed and unrealized P&L at the last price. It counts this browser session's fills; the official
numbers come with the post-trade service (Step 2).

### One-click trading

Tick **1-click** in the order book. A click on an ask (red) then **buys** that quantity at that price, a click on a
bid (green) **sells**. Keep **confirm** ticked until you are used to it.

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

## 7. Run a strategy

Choose the **Quant** layout (or add the **Strategies** panel). Pick one of six textbook strategies, set its
parameters and risk limits, and press **Start**:

| Strategy | Does | Try |
|---|---|---|
| TWAP | Buys or sells a total in equal slices over a time, crossing the spread for each | 200 shares over 5 minutes |
| Iceberg | Shows only a small part of a large order, refilling as it trades | 300 total, 20 visible |
| Grid | Resting buys below and sells above a centre price at fixed steps | ₹1 step, 3 levels |
| Mean reversion | Buys when the price is unusually far below its recent average, sells when far above | Works well here: the simulated fair value mean-reverts |
| Momentum | Long while a fast average is above a slow one, short while below | Works in bursts; try the volatile scenario |
| Market maker | Quotes both sides around the mid, leaning against its inventory | Compete with the simulated market maker |

Each strategy trades in **its own account** (`algo-<strategy>-<symbol>`), so its orders and P&L never mix with
yours. Before every order it checks the **risk limits**: order size, worst-case position (as if all its open orders
filled), distance from the last price and orders per minute; refused orders are logged, not sent. At the **loss
limit** it stops and cancels its orders. Its card shows P&L with a sparkline, position, orders and fills, and a log
of every decision.

Strategies run in this tab once a second. **Stop** or **Stop all** cancels their orders; so does closing the tab.
The code is short on purpose: `apps/web/src/strategies/library.ts`.

## 8. Admin console

Sign in as **admin1** (`grep PRAYOG_ADMIN1_PASSWORD .env`) and open the **Admin** tab: halt or open the market,
change the clock speed, switch the simulated traders between calm and volatile, inject a "news" price jump, run the
self-test with one click, manage accounts and replay the last minutes of any symbol from the journal. Details:
[runbook 13](../runbook/13-admin-console.md).

## 9. Stop

```sh
make down    # stops everything, keeps the market's history
make reset   # stops everything and deletes all local data (a brand-new market next time)
```

If something looks wrong, see the [runbook's debugging page](../runbook/07-debugging.md).
