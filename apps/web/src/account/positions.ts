// Position and P&L from your own fills, average-cost method. Prices are paise. A client-side estimate for this
// browser session until the post-trade service (S16) keeps the official numbers.

export interface Position {
  quantity: number // shares: positive long, negative short
  averagePrice: number // paise per share of the open quantity
  realized: number // paise
}

export const FLAT: Position = { quantity: 0, averagePrice: 0, realized: 0 }

/** Applies one fill: `side` is your side, so a BUY adds shares. */
export function applyFill(
  p: Position,
  side: 'BUY' | 'SELL',
  quantity: number,
  price: number,
): Position {
  const signed = side === 'BUY' ? quantity : -quantity
  if (p.quantity === 0 || Math.sign(p.quantity) === Math.sign(signed)) {
    const total = Math.abs(p.quantity) + quantity
    return {
      quantity: p.quantity + signed,
      averagePrice:
        (p.averagePrice * Math.abs(p.quantity) + price * quantity) / total,
      realized: p.realized,
    }
  }
  // Reducing (or flipping) the position: the closed shares realize P&L against the average price.
  const closing = Math.min(quantity, Math.abs(p.quantity))
  const realized =
    p.realized + closing * (price - p.averagePrice) * Math.sign(p.quantity)
  const next = p.quantity + signed
  if (next === 0) return { quantity: 0, averagePrice: 0, realized }
  if (Math.sign(next) !== Math.sign(p.quantity))
    return { quantity: next, averagePrice: price, realized }
  return { quantity: next, averagePrice: p.averagePrice, realized }
}

/** Unrealized P&L in paise at `mark` (normally the last trade price). */
export function unrealized(p: Position, mark: number): number {
  return p.quantity === 0 || !mark ? 0 : p.quantity * (mark - p.averagePrice)
}
