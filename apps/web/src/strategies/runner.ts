// Runs one strategy: every second it reads the market and its own open orders, asks the strategy what to do, vets
// each action against the risk limits and sends it. Position and P&L come from its own fills.
import type { AccountApi } from '../api/rest'
import type { OpenOrder, Side } from '../api/types'
import {
  applyFill,
  FLAT,
  unrealized,
  type Position,
} from '../account/positions'
import { lossLimitHit, vet, type RiskLimits } from './risk'
import type { LogLine, StrategyView } from './strategySlice'
import type { Action, Context, Strategy } from './types'

export interface MarketView {
  bestBid: number
  bestAsk: number
  last: number
  prices: number[]
  tick: number
}

export interface RunnerDeps {
  api: AccountApi
  token: () => Promise<string>
  market: (symbol: string) => MarketView
  clock: () => number // wall-clock ms
  report: (patch: Partial<StrategyView>, log: LogLine[]) => void
}

export class Runner {
  private position: Position = FLAT
  private readonly seenTrades = new Set<number>()
  private readonly sentAt: number[] = []
  private readonly started: number
  private busy = false
  private stopped = false
  private orders = 0
  private rejected = 0
  private fills = 0
  private readonly pnlHistory: number[] = []
  private pending: LogLine[] = []

  private readonly strategy: Strategy
  private readonly symbol: string
  private readonly limits: RiskLimits
  private readonly deps: RunnerDeps

  constructor(
    strategy: Strategy,
    symbol: string,
    limits: RiskLimits,
    deps: RunnerDeps,
  ) {
    this.strategy = strategy
    this.symbol = symbol
    this.limits = limits
    this.deps = deps
    this.started = deps.clock()
  }

  /** A fill of this strategy's account, from the private feed or an order's answer; each trade counts once. */
  fill(
    tradeId: number,
    side: Side,
    quantity: number,
    price: number,
    symbol: string,
  ) {
    if (symbol !== this.symbol || this.seenTrades.has(tradeId)) return
    this.seenTrades.add(tradeId)
    this.fills++
    this.position = applyFill(this.position, side, quantity, price)
    this.log(
      `${side === 'BUY' ? 'bought' : 'sold'} ${quantity} @ ${(price / 100).toFixed(2)} → position ${this.position.quantity}`,
      'fill',
    )
  }

  get isStopped() {
    return this.stopped
  }

  pnl(mark: number) {
    return this.position.realized + unrealized(this.position, mark)
  }

  /** One decision round. Skipped if the previous one is still waiting on the exchange. */
  async tick(): Promise<void> {
    if (this.busy || this.stopped) return
    this.busy = true
    try {
      const market = this.deps.market(this.symbol)
      const open: OpenOrder[] = (
        await this.deps.token().then((t) => this.deps.api.openOrders(t))
      ).filter((o) => o.symbol === this.symbol)
      const now = this.deps.clock() - this.started
      const pnl = this.pnl(market.last)
      this.pnlHistory.push(Math.round(pnl))
      if (this.pnlHistory.length > 300) this.pnlHistory.shift()
      if (lossLimitHit(pnl, this.limits)) {
        await this.stop(
          'stopped',
          `loss limit hit: P&L ₹${(pnl / 100).toFixed(2)}`,
        )
        return
      }
      const ctx: Context = {
        now,
        symbol: this.symbol,
        tick: market.tick,
        bestBid: market.bestBid,
        bestAsk: market.bestAsk,
        last: market.last,
        prices: market.prices,
        position: this.position.quantity,
        filled: this.fills,
        openOrders: open,
      }
      for (const action of this.strategy.decide(ctx)) {
        if (this.stopped) break
        await this.execute(action, ctx)
      }
    } catch (e) {
      this.log(`error: ${(e as Error).message}`, 'error')
    } finally {
      this.busy = false
      this.flush()
    }
  }

  private async execute(action: Action, ctx: Context) {
    if (action.type === 'done') {
      await this.stop('done', action.why)
      return
    }
    const verdict = vet(action, ctx, this.limits, this.sentAt)
    if (!verdict.ok) {
      this.rejected++
      this.log(`risk refused ${describe(action)}: ${verdict.reason}`, 'risk')
      return
    }
    const token = await this.deps.token()
    if (action.type === 'cancel') {
      await this.deps.api.cancel(token, action.orderId).catch(() => undefined) // already gone is fine
      this.log(`cancel ${action.orderId} (${action.why})`, 'action')
      return
    }
    this.sentAt.push(this.deps.clock() - this.started)
    if (this.sentAt.length > 500) this.sentAt.shift()
    this.orders++
    const r = await this.deps.api.place(token, {
      symbol: this.symbol,
      side: action.side,
      type: 'LIMIT',
      price: action.price,
      quantity: action.quantity,
    })
    this.log(
      `${describe(action)} → ${r.status}${r.reason ? ` (${r.reason})` : ''} · ${action.why}`,
      r.status === 'rejected' ? 'error' : 'action',
    )
    if (r.status === 'rejected') this.rejected++
    for (const f of r.fills)
      this.fill(f.tradeId, action.side, f.quantity, f.price, this.symbol)
  }

  /** Stops deciding, cancels this strategy's orders. Its position stays: close it by hand if you want to. */
  async stop(status: 'stopped' | 'done' | 'error', reason: string) {
    if (this.stopped) return
    this.stopped = true
    this.deps.report({ status: 'stopping' }, [])
    try {
      const token = await this.deps.token()
      const r = await this.deps.api.cancelAll(token)
      this.log(`${reason}; cancelled ${r.cancelled} open orders`, 'info')
    } catch (e) {
      this.log(
        `${reason}; cancelling orders failed: ${(e as Error).message}`,
        'error',
      )
    }
    this.flush({ status, reason })
  }

  private log(text: string, kind: LogLine['kind']) {
    this.pending.push({ t: this.deps.clock(), text, kind })
  }

  private flush(extra: Partial<StrategyView> = {}) {
    const mark = this.deps.market(this.symbol).last
    this.deps.report(
      {
        position: this.position.quantity,
        averagePrice: this.position.averagePrice,
        realized: this.position.realized,
        unrealized: unrealized(this.position, mark),
        orders: this.orders,
        rejected: this.rejected,
        fills: this.fills,
        pnlHistory: [...this.pnlHistory],
        ...extra,
      },
      this.pending,
    )
    this.pending = []
  }
}

function describe(a: Action): string {
  if (a.type === 'place')
    return `${a.side.toLowerCase()} ${a.quantity} @ ${(a.price / 100).toFixed(2)}`
  if (a.type === 'cancel') return `cancel ${a.orderId}`
  return 'done'
}
