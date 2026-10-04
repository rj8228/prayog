// The market as the browser knows it: one book per symbol, rebuilt exactly from the feed (snapshot, then every
// numbered change). A missing seq marks the symbol stale; the feed then reconnects for a fresh snapshot.
import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type {
  MarketMessage,
  SessionState,
  Ticker,
  TradeMessage,
} from '../api/types'

export interface SymbolBook {
  seq: number
  bids: Record<string, [number, number]> // price -> [quantity, orders]
  asks: Record<string, [number, number]>
  trades: TradeMessage[] // oldest first, capped
  ticker: Ticker | null
  stale: boolean
}

export interface MarketState {
  books: Record<string, SymbolBook>
  session: SessionState
  simTime: number
  connected: boolean
}

export const MAX_TRADES = 3000

const initialState: MarketState = {
  books: {},
  session: 'CLOSED',
  simTime: 0,
  connected: false,
}

function applyTrade(book: SymbolBook, trade: TradeMessage) {
  book.trades.push(trade)
  if (book.trades.length > MAX_TRADES)
    book.trades.splice(0, book.trades.length - MAX_TRADES)
  if (book.ticker) {
    const t = book.ticker
    if (t.trades === 0) {
      t.open = trade.price
      t.high = trade.price
      t.low = trade.price
    }
    t.last = trade.price
    t.high = Math.max(t.high, trade.price)
    t.low = Math.min(t.low, trade.price)
    t.volume += trade.quantity
    t.trades += 1
  }
}

function bestPrices(book: SymbolBook) {
  if (!book.ticker) return
  const bids = Object.keys(book.bids).map(Number)
  const asks = Object.keys(book.asks).map(Number)
  book.ticker.bestBid = bids.length ? Math.max(...bids) : 0
  book.ticker.bestAsk = asks.length ? Math.min(...asks) : 0
}

const marketSlice = createSlice({
  name: 'market',
  initialState,
  reducers: {
    connected(state, action: PayloadAction<boolean>) {
      state.connected = action.payload
    },
    message(state, action: PayloadAction<MarketMessage>) {
      const m = action.payload
      if (m.type === 'heartbeat') {
        state.simTime = Math.max(state.simTime, m.simTime)
        return
      }
      if (m.type === 'session') {
        state.session = m.state
        state.simTime = Math.max(state.simTime, m.simTime)
        return
      }
      if (m.type === 'snapshot') {
        state.session = m.session
        const previous = state.books[m.symbol]
        state.books[m.symbol] = {
          seq: m.seq,
          bids: Object.fromEntries(
            m.bids.map((l) => [String(l.price), [l.quantity, l.orders]]),
          ),
          asks: Object.fromEntries(
            m.asks.map((l) => [String(l.price), [l.quantity, l.orders]]),
          ),
          // Keep history loaded earlier (REST) and add the snapshot's recent trades not already there.
          trades: mergeTrades(previous?.trades ?? [], m.trades),
          ticker: { ...m.ticker },
          stale: false,
        }
        return
      }
      const book = state.books[m.symbol]
      if (!book || book.stale) return
      if (m.seq !== book.seq + 1) {
        book.stale = true // a message was lost: never show a wrong book
        return
      }
      book.seq = m.seq
      if (m.type === 'trade') {
        applyTrade(book, m)
        state.simTime = Math.max(state.simTime, m.simTime)
        return
      }
      for (const c of m.changes) {
        const side = c.side === 'BUY' ? book.bids : book.asks
        if (c.quantity === 0) delete side[String(c.price)]
        else side[String(c.price)] = [c.quantity, c.orders]
      }
      bestPrices(book)
    },
    history(
      state,
      action: PayloadAction<{ symbol: string; trades: TradeMessage[] }>,
    ) {
      const book = state.books[action.payload.symbol]
      if (book) book.trades = mergeTrades(action.payload.trades, book.trades)
    },
  },
})

/** Union of two oldest-first trade lists, by trade id. */
export function mergeTrades(
  a: TradeMessage[],
  b: TradeMessage[],
): TradeMessage[] {
  const byId = new Map<number, TradeMessage>()
  for (const t of a) byId.set(t.tradeId, t)
  for (const t of b) byId.set(t.tradeId, t)
  const merged = [...byId.values()].sort((x, y) => x.tradeId - y.tradeId)
  return merged.slice(Math.max(0, merged.length - MAX_TRADES))
}

export const { connected, message, history } = marketSlice.actions
export default marketSlice.reducer

/** Best-first [price, quantity, orders] levels. */
export function levels(
  book: SymbolBook | undefined,
  side: 'BUY' | 'SELL',
  depth: number,
) {
  if (!book) return []
  const entries = Object.entries(side === 'BUY' ? book.bids : book.asks).map(
    ([price, [quantity, orders]]) => [Number(price), quantity, orders] as const,
  )
  entries.sort((x, y) => (side === 'BUY' ? y[0] - x[0] : x[0] - y[0]))
  return entries.slice(0, depth)
}
