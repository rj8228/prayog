import { describe, expect, it, vi } from 'vitest'
import type { AccountApi } from '../api/rest'
import type { OpenOrder, OrderResult } from '../api/types'
import { DEFAULT_LIMITS } from './risk'
import { Runner, type MarketView } from './runner'
import type { LogLine, StrategyView } from './strategySlice'
import type { Action, Strategy } from './types'

function fakeExchange() {
  const open: OpenOrder[] = []
  let nextTrade = 1
  const result = (over: Partial<OrderResult>): OrderResult => ({
    status: 'resting',
    orderId: 1,
    clientOrderId: null,
    symbol: 'INFY',
    reason: null,
    filledQuantity: 0,
    leavesQuantity: 0,
    fills: [],
    inputSeq: 1,
    simTime: 0,
    ...over,
  })
  const api = {
    openOrders: vi.fn(async () => [...open]),
    // Every order fills completely at its price.
    place: vi.fn(async (_t: string, o: { quantity: number; price?: number }) =>
      result({
        status: 'filled',
        filledQuantity: o.quantity,
        fills: [
          { tradeId: nextTrade++, price: o.price!, quantity: o.quantity },
        ],
      }),
    ),
    cancel: vi.fn(async () => result({ status: 'cancelled' })),
    cancelAll: vi.fn(async () => ({ requested: 2, cancelled: 2 })),
  }
  return { api: api as unknown as AccountApi & typeof api }
}

function setup(decide: Strategy['decide'], market: Partial<MarketView> = {}) {
  const ex = fakeExchange()
  const reports: { patch: Partial<StrategyView>; log: LogLine[] }[] = []
  const view: MarketView = {
    bestBid: 100_000,
    bestAsk: 100_010,
    last: 100_005,
    prices: [],
    tick: 5,
    ...market,
  }
  const runner = new Runner({ decide }, 'INFY', DEFAULT_LIMITS, {
    api: ex.api,
    token: async () => 'token',
    market: () => view,
    clock: () => 1_000,
    report: (patch, log) => reports.push({ patch, log }),
  })
  const logs = () =>
    reports.flatMap((r) => r.log.map((l) => `${l.kind}: ${l.text}`))
  const last = () =>
    Object.assign({}, ...reports.map((r) => r.patch)) as Partial<StrategyView>
  return { ex, runner, view, logs, last }
}

const buy = (quantity: number, price = 100_010): Action => ({
  type: 'place',
  side: 'BUY',
  price,
  quantity,
  why: 'test',
})

describe('strategy runner', () => {
  it('sends vetted orders and tracks the position from fills, counting each trade once', async () => {
    const { runner, ex, last } = setup(() => [buy(10)])
    await runner.tick()
    expect(ex.api.place).toHaveBeenCalledWith('token', {
      symbol: 'INFY',
      side: 'BUY',
      type: 'LIMIT',
      price: 100_010,
      quantity: 10,
    })
    runner.fill(1, 'BUY', 10, 100_010, 'INFY') // the same trade again, from the private feed
    runner.fill(99, 'BUY', 5, 100_000, 'TCS') // another symbol: not ours
    await runner.tick()
    expect(last()).toMatchObject({ position: 20, orders: 2, fills: 2 })
  })

  it('logs and counts orders the risk checks refuse, without sending them', async () => {
    const { runner, ex, logs, last } = setup(() => [buy(500)])
    await runner.tick()
    expect(ex.api.place).not.toHaveBeenCalled()
    expect(logs()).toEqual([
      expect.stringMatching(
        /^risk: risk refused buy 500 @ 1000.10: order of 500 is above/,
      ),
    ])
    expect(last().rejected).toBe(1)
  })

  it('stops, cancelling everything, when the loss limit is hit', async () => {
    const { runner, ex, view, last, logs } = setup(() => [])
    runner.fill(1, 'BUY', 100, 100_000, 'INFY')
    view.last = 97_000 // 100 shares × ₹30 = ₹3,000 loss, limit ₹2,000
    await runner.tick()
    expect(ex.api.cancelAll).toHaveBeenCalled()
    expect(runner.isStopped).toBe(true)
    expect(last()).toMatchObject({ status: 'stopped' })
    expect(last().reason).toMatch(/loss limit hit/)
    expect(logs().at(-1)).toMatch(/cancelled 2 open orders/)
    await runner.tick()
    expect(ex.api.openOrders).toHaveBeenCalledTimes(1) // no more decisions
  })

  it('a strategy that is done stops itself', async () => {
    const { runner, last } = setup(() => [
      { type: 'done', why: 'traded all 100' },
    ])
    await runner.tick()
    expect(last()).toMatchObject({ status: 'done', reason: 'traded all 100' })
  })

  it('keeps running after an exchange error', async () => {
    const { runner, ex, logs } = setup(() => [buy(1)])
    ex.api.place.mockRejectedValueOnce(new Error('429: rate limited'))
    await runner.tick()
    expect(logs()).toContain('error: error: 429: rate limited')
    await runner.tick()
    expect(ex.api.place).toHaveBeenCalledTimes(2)
  })
})
