// Shapes of the exchange's JSON. Prices are integer paise; times are sim time in epoch microseconds.

export type Side = 'BUY' | 'SELL'
export type SessionState = 'OPEN' | 'HALTED' | 'CLOSED'

export interface Level {
  price: number
  quantity: number
  orders: number
}

export interface TradeMessage {
  type: 'trade'
  symbol: string
  seq: number
  tradeId: number
  price: number
  quantity: number
  aggressor: Side
  simTime: number
}

export interface Ticker {
  symbol: string
  referencePrice: number
  last: number
  open: number
  high: number
  low: number
  volume: number
  trades: number
  bestBid: number
  bestAsk: number
}

export interface Snapshot {
  type: 'snapshot'
  symbol: string
  seq: number
  session: SessionState
  bids: Level[]
  asks: Level[]
  trades: TradeMessage[]
  ticker: Ticker
}

export interface BookDelta {
  type: 'book'
  symbol: string
  seq: number
  changes: { side: Side; price: number; quantity: number; orders: number }[]
}

export interface SessionMessage {
  type: 'session'
  state: SessionState
  simTime: number
}

export interface Heartbeat {
  type: 'heartbeat'
  simTime: number
}

export type MarketMessage =
  Snapshot | BookDelta | TradeMessage | SessionMessage | Heartbeat

export interface Instrument {
  symbol: string
  tickSize: number
  maxOrderQuantity: number
  referencePrice: number
  bandPercent: number
  bandLow: number
  bandHigh: number
}

export interface SessionInfo {
  state: SessionState
  simTime: number
  clockMultiplier: number
  open: string
  close: string
  offset: string
}

export interface OrderResult {
  status: 'resting' | 'filled' | 'cancelled' | 'modified' | 'rejected'
  orderId: number
  clientOrderId: string | null
  symbol: string | null
  reason: string | null
  filledQuantity: number
  leavesQuantity: number
  fills: { tradeId: number; price: number; quantity: number }[]
  inputSeq: number
  simTime: number
}

export interface OpenOrder {
  orderId: number
  clientOrderId: string | null
  symbol: string
  side: Side
  price: number
  quantity: number
  leavesQuantity: number
  filledQuantity: number
}

export interface Me {
  subject: string
  accountLabel: string
  accountId: number
  clientId: string
  roles: string[]
}

export interface Fill {
  type: 'fill'
  eventSeq: number
  simTime: number
  tradeId: number
  orderId: number
  symbol: string
  side: Side
  price: number
  quantity: number
  aggressor: boolean
}

export interface OrderUpdate {
  type: 'order'
  status: 'accepted' | 'rejected' | 'cancelled' | 'modified'
  eventSeq: number
  orderId: number
  symbol: string
  reason: string | null
}
