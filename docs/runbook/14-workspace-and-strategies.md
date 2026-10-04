# 14. Trading workspace and strategies

Sign in at http://app.prayog.localhost (trader1 or admin1).

| Do | Expect |
|---|---|
| Drag a panel by its title | It moves; others make room. The Layout menu shows **Custom (saved)** |
| Drag a panel's bottom-right corner | It resizes |
| **⇄** on a panel, pick another | The two trade places and sizes |
| **+ Add panel** → Watchlist | Every symbol with last, change, spread and a sparkline; click one to select it |
| Reload the page | Your layout comes back |
| Press `?` | The keyboard shortcuts |
| **Explain** (or `E`) | A note on every panel with a docs link |
| Order book: tick **1-click**, untick **confirm**, click the best ask | Bought at that price; a notification; **Positions & P&L** shows the position |
| Layout **Quant**, Strategies: **Market maker**, **Start** | A card with status running; within seconds orders, fills and a P&L sparkline |
| **Show log** on the card | Every decision: quotes, cancels, fills, risk refusals |
| Start **Mean reversion** with Risk limits → Max order 5 | Its 20-share orders are refused by risk and logged, never sent |
| **Stop all** | Status stopped; the strategy accounts have no open orders (Admin → Accounts) |

## When something is off

- **Panels overlap or vanish:** choose a preset to reset the layout. To wipe saved layouts, in the browser
  console: `Object.keys(localStorage).filter(k => k.startsWith('prayog.workspace')).forEach(k => localStorage.removeItem(k))`.
- **A strategy shows `error:` lines:** usually an expired session (sign in again) or the exchange's rate limit
  (fewer strategies, or a slower one). It keeps running and retries every second.
- **Leftover strategy orders:** starting the same strategy on the same symbol cancels them first; or as admin,
  **Accounts → Cancel all** on the `algo-...` account.
