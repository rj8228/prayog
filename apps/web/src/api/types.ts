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

export interface JourneyStep {
  eventSeq: number
  simTime: number
  event: string
  detail: Record<string, string | number>
}

export interface Journey {
  orderId: number
  accountId: number
  steps: JourneyStep[]
}

export interface Simulation {
  version: number
  scenario: string | null
  paused: boolean
  jumps: { id: number; symbol: string | null; percent: number }[]
}

/** GET /api/v1/ops/status (roles ops, admin). */
export interface OpsStatus {
  recoveredFromJournal: boolean
  lastProcessedInputSeq: number
  ringCapacity: number
  ringRemaining: number
  publishErrors: number
  simTime: number
  clockMultiplier: number
  session: SessionState
  kafka?: {
    enabled: boolean
    connected: boolean
    publishedSeq: number
    lag: number
    errors: number
  }
  recoveredFromSnapshotInputSeq?: number
  lastSnapshotInputSeq?: number
}

export interface Overview {
  status: OpsStatus
  orders: Record<string, number>
  latencyMillis: Record<string, number>
  journalBytes: number
  marketDataSubscribers: number
  accounts: number
  simulation: Simulation
}

export interface AccountView {
  account: {
    accountId: number
    username: string
    label: string
    clientId: string
    roles: string[]
    lastSeen: number
    requests: number
    rejected: number
  }
  openOrders: number
}

export interface Check {
  name: string
  ok: boolean
  detail: string
  millis: number
}

export interface ReplayWindow {
  symbol: string
  fromSimTime: number
  toSimTime: number
  bids: Level[]
  asks: Level[]
  frames: {
    simTime: number
    changes: { side: Side; price: number; quantity: number; orders: number }[]
    trades: {
      tradeId: number
      price: number
      quantity: number
      aggressor: Side
    }[]
  }[]
  truncated: boolean
}

// ---- post-trade (ADR 0015): official positions, P&L, history and the leaderboard ----

export interface PositionView {
  symbol: string
  quantity: number
  averagePrice: number
  cost: number
  mark: number
  realisedPnl: number
  unrealisedPnl: number
  charges: number
  netPnl: number
  bought: number
  sold: number
  trades: number
}

export interface PnlSummary {
  accountId: number
  label: string
  realisedPnl: number
  unrealisedPnl: number
  charges: number
  netPnl: number
  trades: number
  rank: number
  positions: PositionView[]
}

export interface LedgerFill {
  tradeId: number
  orderId: number
  symbol: string
  side: Side
  price: number
  quantity: number
  charges: number
  realisedPnl: number
  simTime: number
  eventId: number
}

export interface LedgerOrder {
  orderId: number
  clientOrderId: string
  symbol: string
  side: Side
  orderType: 'LIMIT' | 'MARKET'
  price: number
  quantity: number
  filledQuantity: number
  leavesQuantity: number
  status: 'open' | 'partially_filled' | 'filled' | 'cancelled'
  reason: string | null
  createdAt: number
  updatedAt: number
}

export interface LedgerRejection {
  eventId: number
  clientOrderId: string
  symbol: string
  reason: string
  simTime: number
}

export interface Leaderboard {
  accounts: number
  entries: {
    rank: number
    accountId: number
    name: string | null
    netPnl: number
  }[]
}

export interface PostTradeStatus {
  trades: number
  accounts: number
  pnlBeforeCharges: number
  charges: number
  netQuantityBySymbol: Record<string, number>
  lastEventIdByPartition: Record<string, number>
  eventsConsumed: number
}
