import { describe, expect, it } from 'vitest'
import type { ReplayWindow } from '../api/types'
import { bookAt } from './replayBook'

const window: ReplayWindow = {
  symbol: 'INFY',
  fromSimTime: 0,
  toSimTime: 10,
  bids: [{ price: 100, quantity: 5, orders: 1 }],
  asks: [{ price: 110, quantity: 7, orders: 1 }],
  frames: [
    {
      simTime: 1,
      changes: [{ side: 'SELL', price: 110, quantity: 3, orders: 1 }],
      trades: [],
    },
    {
      simTime: 2,
      changes: [{ side: 'BUY', price: 100, quantity: 0, orders: 0 }],
      trades: [],
    },
  ],
  truncated: false,
}

describe('replay viewer', () => {
  it('rebuilds the book at any frame from the starting snapshot', () => {
    expect([...bookAt(window, 0).asks]).toEqual([[110, 7]])
    expect([...bookAt(window, 1).asks]).toEqual([[110, 3]])
    expect([...bookAt(window, 2).bids]).toEqual([])
  })
})
