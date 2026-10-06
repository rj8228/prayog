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
    text: "Your official position per symbol from the post-trade ledger: every fill ever (average-cost method), realised P&L from closed shares, unrealised P&L at the last trade price, charges, and your leaderboard rank. If post-trade is unreachable, an estimate from this session's fills is shown instead.",
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
  blotter: {
    text: "Your order history, fills (with charges and realised P&L) and refused orders, from the post-trade ledger. It is built from the exchange's Kafka event stream, so it survives restarts and lags the exchange by milliseconds.",
    link: `${DOCS}/adr/0015-post-trade-ledger-and-leaderboard/`,
    label: 'Post-trade ledger',
  },
  leaderboard: {
    text: 'Every account ranked by net P&L (realised + unrealised - charges), kept in a Redis sorted set by the post-trade service. Your own row is in bold once you are on it.',
    link: `${DOCS}/adr/0015-post-trade-ledger-and-leaderboard/`,
    label: 'Leaderboard',
  },
}
