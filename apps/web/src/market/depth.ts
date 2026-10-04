// The numbers behind the depth chart, kept out of the component so they can be tested.

type Level = readonly [number, number, number] // price, quantity, orders
export type Step = readonly [number, number] // price, cumulative quantity up to that price

export interface DepthView {
  bids: Step[] // best (highest) first
  asks: Step[] // best (lowest) first
  lo: number
  hi: number
  mid: number
  top: number // largest cumulative quantity shown
}

/** Running total of quantity, best price first. */
export function cumulative(levels: readonly Level[]): Step[] {
  const steps: Step[] = []
  for (const [price, quantity] of levels)
    steps.push([price, (steps.at(-1)?.[1] ?? 0) + quantity])
  return steps
}

/**
 * A price window centred on the mid, as wide as the nearer side's deepest level, so one far-away order (a stray ask
 * at +10%) cannot squash the other side into a sliver. Levels outside the window are left out.
 */
export function depthView(
  bids: readonly Level[],
  asks: readonly Level[],
): DepthView | null {
  if (!bids.length || !asks.length) return null
  const mid = (bids[0][0] + asks[0][0]) / 2
  const reach = Math.max(
    Math.min(mid - bids[bids.length - 1][0], asks[asks.length - 1][0] - mid),
    asks[0][0] - mid, // always show at least the touch
  )
  const lo = mid - reach
  const hi = mid + reach
  const b = cumulative(bids).filter(([p]) => p >= lo)
  const a = cumulative(asks).filter(([p]) => p <= hi)
  const top = Math.max(b.at(-1)?.[1] ?? 0, a.at(-1)?.[1] ?? 0, 1)
  return { bids: b, asks: a, lo, hi, mid, top }
}
