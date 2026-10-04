import { describe, expect, it } from 'vitest'
import { applyFill, FLAT, unrealized } from './positions'

describe('positions (average cost)', () => {
  it('averages buys and realizes on a partial sell', () => {
    let p = applyFill(FLAT, 'BUY', 10, 100_00)
    p = applyFill(p, 'BUY', 10, 110_00)
    expect(p).toEqual({ quantity: 20, averagePrice: 105_00, realized: 0 })
    p = applyFill(p, 'SELL', 5, 120_00)
    expect(p).toEqual({
      quantity: 15,
      averagePrice: 105_00,
      realized: 5 * 15_00,
    })
    expect(unrealized(p, 100_00)).toBe(15 * -5_00)
  })

  it('handles shorts and flips', () => {
    let p = applyFill(FLAT, 'SELL', 10, 200_00)
    expect(unrealized(p, 190_00)).toBe(10 * 10_00) // short gains when price falls
    p = applyFill(p, 'BUY', 15, 190_00) // cover 10, then 5 long at 190
    expect(p).toEqual({
      quantity: 5,
      averagePrice: 190_00,
      realized: 10 * 10_00,
    })
  })

  it('goes flat cleanly', () => {
    let p = applyFill(FLAT, 'BUY', 3, 50_00)
    p = applyFill(p, 'SELL', 3, 49_00)
    expect(p).toEqual({ quantity: 0, averagePrice: 0, realized: -3_00 })
    expect(unrealized(p, 99_00)).toBe(0)
  })
})
