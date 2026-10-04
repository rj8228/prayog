import { describe, expect, it } from 'vitest'
import type { BookDelta, Snapshot, TradeMessage } from '../api/types'
import reducer, { levels, mergeTrades, message } from './marketSlice'

const snapshot: Snapshot = {
  type: 'snapshot',
  symbol: 'INFY',
  seq: 10,
  session: 'OPEN',
  bids: [
    { price: 149900, quantity: 10, orders: 1 },
    { price: 149800, quantity: 5, orders: 2 },
  ],
  asks: [{ price: 150000, quantity: 7, orders: 1 }],
  trades: [],
  ticker: {
    symbol: 'INFY',
    referencePrice: 150000,
    last: 0,
    open: 0,
    high: 0,
    low: 0,
    volume: 0,
    trades: 0,
    bestBid: 149900,
    bestAsk: 150000,
  },
}

const trade = (seq: number, tradeId: number, price: number): TradeMessage => ({
  type: 'trade',
  symbol: 'INFY',
  seq,
  tradeId,
  price,
  quantity: 3,
  aggressor: 'BUY',
  simTime: 1_000_000 * tradeId,
})

const delta = (seq: number, changes: BookDelta['changes']): BookDelta => ({
  type: 'book',
  symbol: 'INFY',
  seq,
  changes,
})

describe('market slice', () => {
  it('rebuilds the book from a snapshot and numbered changes', () => {
    let state = reducer(undefined, message(snapshot))
    state = reducer(state, message(trade(11, 1, 150000)))
    state = reducer(
      state,
      message(
        delta(12, [
          { side: 'SELL', price: 150000, quantity: 4, orders: 1 },
          { side: 'BUY', price: 149950, quantity: 2, orders: 1 },
        ]),
      ),
    )
    const infy = state.books.INFY
    expect(levels(infy, 'BUY', 5)).toEqual([
      [149950, 2, 1],
      [149900, 10, 1],
      [149800, 5, 2],
    ])
    expect(levels(infy, 'SELL', 5)).toEqual([[150000, 4, 1]])
    expect(infy.ticker?.last).toBe(150000)
    expect(infy.ticker?.bestBid).toBe(149950)
    expect(infy.ticker?.volume).toBe(3)
  })

  it('a missing seq marks the book stale and ignores later changes', () => {
    let state = reducer(undefined, message(snapshot))
    state = reducer(
      state,
      message(delta(12, [{ side: 'BUY', price: 1, quantity: 1, orders: 1 }])),
    )
    expect(state.books.INFY.stale).toBe(true)
    state = reducer(
      state,
      message(delta(13, [{ side: 'BUY', price: 2, quantity: 1, orders: 1 }])),
    )
    expect(levels(state.books.INFY, 'BUY', 10).map((l) => l[0])).not.toContain(
      2,
    )
    // A fresh snapshot repairs it.
    state = reducer(state, message({ ...snapshot, seq: 40 }))
    expect(state.books.INFY.stale).toBe(false)
  })

  it('a zero quantity removes the level', () => {
    let state = reducer(undefined, message(snapshot))
    state = reducer(
      state,
      message(
        delta(11, [{ side: 'SELL', price: 150000, quantity: 0, orders: 0 }]),
      ),
    )
    expect(levels(state.books.INFY, 'SELL', 5)).toEqual([])
  })

  it('tracks the session and sim time', () => {
    let state = reducer(undefined, message(snapshot))
    state = reducer(
      state,
      message({ type: 'session', state: 'HALTED', simTime: 5 }),
    )
    state = reducer(state, message({ type: 'heartbeat', simTime: 9 }))
    expect(state.session).toBe('HALTED')
    expect(state.simTime).toBe(9)
  })

  it('merges trade history by id, oldest first', () => {
    const merged = mergeTrades(
      [trade(1, 1, 1), trade(2, 2, 2)],
      [trade(2, 2, 2), trade(3, 3, 3)],
    )
    expect(merged.map((t) => t.tradeId)).toEqual([1, 2, 3])
  })
})
