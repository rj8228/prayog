import { describe, expect, it } from 'vitest'
import { cumulative, depthView } from './depth'

describe('depth chart numbers', () => {
  it('accumulates quantity from the best price outwards', () => {
    expect(
      cumulative([
        [100, 5, 1],
        [99, 7, 2],
      ]),
    ).toEqual([
      [100, 5],
      [99, 12],
    ])
  })

  it('centres on the mid and ignores a far-away order', () => {
    const bids = [
      [1000, 40, 1],
      [995, 40, 1],
      [990, 40, 1],
    ] as const
    const asks = [
      [1005, 40, 1],
      [1010, 40, 1],
      [1500, 230, 1],
    ] as const
    const v = depthView(bids, asks)!
    expect(v.mid).toBe(1002.5)
    expect([v.lo, v.hi]).toEqual([990, 1015])
    expect(v.bids).toHaveLength(3)
    expect(v.asks).toEqual([
      [1005, 40],
      [1010, 80],
    ]) // the order at 1500 is outside the window
    expect(v.top).toBe(120)
  })

  it('needs both sides', () => {
    expect(depthView([], [[1, 1, 1]])).toBeNull()
  })
})
