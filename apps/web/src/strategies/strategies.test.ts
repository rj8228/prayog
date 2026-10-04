import { describe, expect, it } from 'vitest'
import type { OpenOrder } from '../api/types'
import {
  defaults,
  grid,
  iceberg,
  marketMaker,
  meanReversion,
  momentum,
  twap,
} from './library'
import { ema, roundToTick } from './math'
import { DEFAULT_LIMITS, lossLimitHit, vet } from './risk'
import type { Action, Context } from './types'

function ctx(over: Partial<Context> = {}): Context {
  return {
    now: 0,
    symbol: 'INFY',
    tick: 5,
    bestBid: 150_000,
    bestAsk: 150_025,
    last: 150_010,
    prices: [],
    position: 0,
    filled: 0,
    openOrders: [],
    ...over,
  }
}

const order = (
  orderId: number,
  side: 'BUY' | 'SELL',
  price: number,
  leavesQuantity = 10,
): OpenOrder => ({
  orderId,
  clientOrderId: null,
  symbol: 'INFY',
  side,
  price,
  quantity: leavesQuantity,
  leavesQuantity,
  filledQuantity: 0,
})

const places = (a: Action[]) => a.filter((x) => x.type === 'place')

describe('math', () => {
  it('rounds to the tick and averages', () => {
    expect(roundToTick(150_012, 5)).toBe(150_010)
    expect(ema([10, 10, 10], 5)).toBe(10)
    expect(ema([10, 20], 1)).toBe(20)
  })
})

describe('TWAP', () => {
  const s = () =>
    twap.create({
      ...defaults(twap),
      side: 'BUY',
      total: 100,
      minutes: 1,
      slices: 4,
    })

  it('sends one slice per interval at the ask, catching up to the schedule', () => {
    const t = s()
    expect(places(t.decide(ctx({ now: 0 })))).toEqual([
      expect.objectContaining({ side: 'BUY', price: 150_025, quantity: 25 }),
    ])
    expect(t.decide(ctx({ now: 5_000, position: 25 }))).toEqual([]) // same slice
    // Second slice, only 10 filled so far and 15 still resting: cancel, then send what is due.
    const o = [order(1, 'BUY', 150_025, 15)]
    expect(t.decide(ctx({ now: 15_000, position: 10, openOrders: o }))).toEqual(
      [expect.objectContaining({ type: 'cancel', orderId: 1 })],
    )
    expect(
      places(t.decide(ctx({ now: 15_500, position: 10 }))).map(
        (a) => a.type === 'place' && a.quantity,
      ),
    ).toEqual([40])
  })

  it('finishes when everything is traded and re-prices leftovers after the deadline', () => {
    const t = s()
    t.decide(ctx({ now: 46_000, position: 0 })) // last slice
    expect(
      t.decide(ctx({ now: 61_000, position: 90 })).map((a) => a.type),
    ).toEqual(['place'])
    expect(t.decide(ctx({ now: 62_000, position: 100 }))).toEqual([
      expect.objectContaining({ type: 'done' }),
    ])
  })
})

describe('Iceberg', () => {
  it('shows only the visible size and refills until done', () => {
    const i = iceberg.create({ side: 'SELL', total: 50, display: 20, price: 0 })
    expect(places(i.decide(ctx()))).toEqual([
      expect.objectContaining({ side: 'SELL', price: 150_025, quantity: 20 }),
    ])
    expect(i.decide(ctx({ openOrders: [order(1, 'SELL', 150_025)] }))).toEqual(
      [],
    )
    expect(places(i.decide(ctx({ position: -40 })))).toEqual([
      expect.objectContaining({ quantity: 10 }),
    ])
    expect(i.decide(ctx({ position: -50 }))).toEqual([
      expect.objectContaining({ type: 'done' }),
    ])
  })
})

describe('Grid', () => {
  it('places resting levels around the centre and refills only missing ones', () => {
    const g = grid.create({ step: 1, levels: 2, quantity: 5 })
    const first = places(g.decide(ctx()))
    expect(
      first.map((a) => a.type === 'place' && `${a.side}@${a.price}`),
    ).toEqual(['BUY@149915', 'SELL@150115', 'BUY@149815', 'SELL@150215']) // centre: mid 150,012.5 rounded to the tick
    const open = [
      order(1, 'BUY', 149_915),
      order(2, 'SELL', 150_115),
      order(3, 'SELL', 150_215),
    ]
    expect(
      places(g.decide(ctx({ openOrders: open }))).map(
        (a) => a.type === 'place' && a.price,
      ),
    ).toEqual([149_815])
  })
})

describe('Mean reversion', () => {
  const m = () => meanReversion.create({ lookback: 10, z: 1.5, quantity: 20 })
  const calm = [
    100_000, 100_010, 99_990, 100_005, 99_995, 100_000, 100_010, 99_990,
    100_005,
  ]

  it('buys a big dip and exits back at the mean', () => {
    const s = m()
    expect(
      places(
        s.decide(
          ctx({ last: 99_900, prices: [...calm, 99_900], bestAsk: 99_905 }),
        ),
      ),
    ).toEqual([
      expect.objectContaining({ side: 'BUY', quantity: 20, price: 99_905 }),
    ])
    expect(
      places(
        s.decide(
          ctx({
            last: 100_010,
            prices: [...calm, 100_010],
            position: 20,
            bestBid: 100_005,
          }),
        ),
      ),
    ).toEqual([expect.objectContaining({ side: 'SELL', quantity: 20 })])
  })

  it('waits for enough history', () => {
    expect(m().decide(ctx({ prices: calm }))).toEqual([])
  })
})

describe('Momentum', () => {
  it('goes long in an up-trend and short in a down-trend', () => {
    const up = Array.from({ length: 60 }, (_, i) => 100_000 + i * 50)
    const s = momentum.create({ fast: 5, slow: 20, threshold: 5, quantity: 20 })
    expect(places(s.decide(ctx({ prices: up })))).toEqual([
      expect.objectContaining({ side: 'BUY', quantity: 20 }),
    ])
    const down = Array.from({ length: 60 }, (_, i) => 100_000 - i * 50)
    expect(places(s.decide(ctx({ prices: down, position: 20 })))).toEqual([
      expect.objectContaining({ side: 'SELL', quantity: 20 }),
    ])
  })
})

describe('Market maker', () => {
  const mm = () =>
    marketMaker.create({
      halfSpread: 2,
      quantity: 10,
      skew: 1,
      maxInventory: 50,
    })

  it('quotes both sides around the mid and leans against inventory', () => {
    const flat = places(
      mm().decide(ctx({ bestBid: 150_000, bestAsk: 150_030 })),
    )
    expect(
      flat.map((a) => a.type === 'place' && `${a.side}@${a.price}`),
    ).toEqual(['BUY@150005', 'SELL@150025'])
    const long = places(
      mm().decide(ctx({ bestBid: 150_000, bestAsk: 150_030, position: 20 })),
    )
    expect(long.map((a) => a.type === 'place' && a.price)).toEqual([
      149_995, 150_015,
    ])
  })

  it('requotes a stale price and stops adding at the inventory limit', () => {
    const acts = mm().decide(
      ctx({
        bestBid: 150_000,
        bestAsk: 150_030,
        position: 50,
        openOrders: [order(7, 'BUY', 149_000)],
      }),
    )
    expect(
      acts
        .filter((a) => a.type === 'cancel')
        .map((a) => a.type === 'cancel' && a.orderId),
    ).toEqual([7])
    expect(places(acts).map((a) => a.type === 'place' && a.side)).toEqual([
      'SELL',
    ])
  })
})

describe('risk checks', () => {
  const buy = (quantity: number, price = 150_025): Action => ({
    type: 'place',
    side: 'BUY',
    price,
    quantity,
    why: '',
  })

  it('passes a normal order and always allows cancels', () => {
    expect(vet(buy(10), ctx(), DEFAULT_LIMITS, [])).toEqual({ ok: true })
    expect(
      vet(
        { type: 'cancel', orderId: 1, why: '' },
        ctx({ position: 10_000 }),
        DEFAULT_LIMITS,
        [],
      ).ok,
    ).toBe(true)
  })

  it('refuses size, off-tick, fat-finger, position and rate breaches', () => {
    const reason = (a: Action, c = ctx(), sent: number[] = []) => {
      const v = vet(a, c, DEFAULT_LIMITS, sent)
      return v.ok ? 'ok' : v.reason
    }
    expect(reason(buy(101))).toMatch(/above the 100 share limit/)
    expect(reason(buy(10, 150_023))).toMatch(/not on the tick/)
    expect(reason(buy(10, 160_000))).toMatch(/more than 2%/)
    expect(
      reason(
        buy(50),
        ctx({ position: 100, openOrders: [order(1, 'BUY', 150_000, 60)] }),
      ),
    ).toMatch(/position of 210/)
    expect(
      reason(
        buy(10),
        ctx({ now: 70_000 }),
        Array.from({ length: 60 }, () => 65_000),
      ),
    ).toMatch(/60 orders in the last minute/)
    expect(
      reason(
        buy(10),
        ctx({ now: 70_000 }),
        Array.from({ length: 60 }, () => 5_000),
      ),
    ).toBe('ok') // older than a minute
  })

  it('stops at the loss limit', () => {
    expect(lossLimitHit(-1_999_99, DEFAULT_LIMITS)).toBe(false)
    expect(lossLimitHit(-2_000_00, DEFAULT_LIMITS)).toBe(true)
  })
})
