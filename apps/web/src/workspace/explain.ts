// "Explain mode": what each panel shows and where to learn more on the docs site.
import type { PanelId } from './layouts'

const DOCS = 'https://rj8228.github.io/prayog'

export const EXPLAIN: Record<
  PanelId,
  { text: string; link: string; label: string }
> = {
  chart: {
    text: 'Candles built in your browser from every trade on the feed: open, high, low, close per interval of simulated time. Volume bars underneath.',
    link: `${DOCS}/overview/market-data-feeds.html`,
    label: 'Market-data feeds',
  },
  ladder: {
    text: 'Level 2 data: total quantity and order count at each price, rebuilt exactly from a snapshot plus numbered changes. If a change is missed it says "resyncing" and fetches a fresh snapshot. With 1-click on, clicking an ask buys there and clicking a bid sells there.',
    link: `${DOCS}/overview/market-data-feeds.html`,
    label: 'Snapshots, deltas and sequence numbers',
  },
  tape: {
    text: 'Every trade as it happens. Green: the buyer took liquidity (crossed the spread); red: the seller did.',
    link: `${DOCS}/overview/market-data-feeds.html`,
    label: 'Market-data feeds',
  },
  ticket: {
    text: 'Your order goes through Keycloak-authenticated REST to the exchange, is sequenced in the ring buffer, matched by price then time, and written to the journal before you get this answer. The time shown is the full round trip.',
    link: `${DOCS}/guides/using-the-market/`,
    label: 'Using the market',
  },
  orders: {
    text: 'Your orders still open on the book, as the exchange reports them (it is the source of truth). Click an order id to see its journey from the journal.',
    link: `${DOCS}/overview/journaling.html`,
    label: 'The journal',
  },
  fills: {
    text: 'Each of your trades, pushed to you on the private WebSocket the moment it happens, including resting orders that fill later.',
    link: `${DOCS}/bots/api/`,
    label: 'Private feed (API reference)',
  },
  positions: {
    text: 'Your position per symbol from your own fills (average-cost method), realized P&L from closed shares and unrealized P&L at the last trade price. An estimate for this browser session until the post-trade service (Step 2) keeps the official numbers.',
    link: `${DOCS}/overview/market-making.html`,
    label: 'Inventory and P&L',
  },
  watchlist: {
    text: 'Every symbol at a glance: last price, change against the reference price, spread and a sparkline of recent trades. Click to select.',
    link: `${DOCS}/guides/using-the-market/`,
    label: 'Using the market',
  },
  depth: {
    text: 'Cumulative quantity you could trade at each price: bids climb to the left, asks to the right. A steep wall is a lot of liquidity; a gap means a market order would move the price.',
    link: `${DOCS}/overview/market-making.html`,
    label: 'Liquidity and market making',
  },
  strategies: {
    text: 'Textbook strategies running in this tab, each in its own account (algo-<strategy>-<symbol>) with its own risk limits checked before every order. They use the same public API as any bot; closing the tab stops them and cancels their orders.',
    link: `${DOCS}/bots/`,
    label: 'Building a bot',
  },
}
