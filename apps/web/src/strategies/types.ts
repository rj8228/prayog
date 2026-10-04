// Strategies are pure decision functions: given what they can see (the market and their own account), they return
// actions. The runner sends the actions through risk checks to the exchange. Prices are integer paise.
import type { OpenOrder, Side } from '../api/types'

export interface Context {
  now: number // ms since the strategy started (wall clock: strategies are clients, not the exchange)
  symbol: string
  tick: number // tick size, paise
  bestBid: number // 0 when there is none
  bestAsk: number
  last: number // last trade price, 0 when none yet
  prices: number[] // recent trade prices, oldest first
  position: number // this strategy's shares: positive long, negative short
  filled: number // total shares this strategy has traded
  openOrders: OpenOrder[] // this strategy's open orders on this symbol
}

export type Action =
  | { type: 'place'; side: Side; price: number; quantity: number; why: string }
  | { type: 'cancel'; orderId: number; why: string }
  | { type: 'done'; why: string }

export interface Strategy {
  decide(ctx: Context): Action[]
}

export type ParamValue = number | string

export interface ParamSpec {
  key: string
  label: string
  kind: 'number' | 'rupees' | 'side'
  default: ParamValue
  help: string
  min?: number
}

export interface StrategyKind {
  id: string
  name: string
  summary: string
  params: ParamSpec[]
  create(params: Record<string, ParamValue>): Strategy
}
