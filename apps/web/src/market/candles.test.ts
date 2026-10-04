import { describe, expect, it } from 'vitest'
import type { TradeMessage } from '../api/types'
import { toCandles } from './candles'

const t = (seconds: number, price: number, quantity = 1): TradeMessage => ({
  type: 'trade',
  symbol: 'X',
  seq: 0,
  tradeId: seconds,
  price,
  quantity,
  aggressor: 'BUY',
  simTime: seconds * 1_000_000,
})

describe('toCandles', () => {
  it('groups trades into buckets of sim time', () => {
    const candles = toCandles(
      [t(100, 10), t(105, 14), t(109, 9, 2), t(112, 11)],
      10,
    )
    expect(candles).toEqual([
      { time: 100, open: 10, high: 14, low: 9, close: 9, volume: 4 },
      { time: 110, open: 11, high: 11, low: 11, close: 11, volume: 1 },
    ])
  })

  it('handles no trades', () => {
    expect(toCandles([], 60)).toEqual([])
  })
})
