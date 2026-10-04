import type { ReplayWindow } from '../api/types'

export type Book = { bids: Map<number, number>; asks: Map<number, number> }

/** The book after applying frames [0, index) on top of the window's starting snapshot. */
export function bookAt(w: ReplayWindow, index: number): Book {
  const bids = new Map(w.bids.map((l) => [l.price, l.quantity]))
  const asks = new Map(w.asks.map((l) => [l.price, l.quantity]))
  for (const frame of w.frames.slice(0, index)) {
    for (const c of frame.changes) {
      const side = c.side === 'BUY' ? bids : asks
      if (c.quantity === 0) side.delete(c.price)
      else side.set(c.price, c.quantity)
    }
  }
  return { bids, asks }
}
