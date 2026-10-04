# 10. Trading as a user

The full walkthrough is the [user guide](../guides/using-the-market.md). Quick checks:

| Step | Do | Expect |
|---|---|---|
| Watch | Open http://app.prayog.localhost | Session badge `OPEN`, sim clock ticking, tickers and trades changing every second or two, a two-sided order book |
| Sign in | **Sign in to trade** → `trader1` / `grep PRAYOG_TRADER1_PASSWORD .env` | Back on the market, your name top right, the order ticket enabled |
| Resting order | Buy 15 INFY, Limit, a price well below the market (e.g. 1450.00) | `resting, 15 open`; a new bid level in the order book; the order in **My orders** |
| Immediate fill | Buy 10, Market | `filled 10 @ ...`; the trade in **Trades** and in **My fills** |
| Cancel | **Cancel** (or **Cancel all**) in My orders | The order disappears from My orders and from the book |
| Refused order | Limit price 1495.01 | `rejected (PRICE_NOT_ON_TICK)` |

From a terminal (as the demo bot account): `uv run prayog book INFY`, `uv run prayog buy INFY 10 --price 1495.50`,
`uv run prayog orders`, `uv run prayog cancel-all`, `uv run prayog watch INFY`.
