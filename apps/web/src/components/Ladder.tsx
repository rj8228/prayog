import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'
import { levels } from '../market/marketSlice'

/** Depth ladder: asks above (best at the bottom), bids below (best at the top). Click a price to use it. */
export function Ladder({
  symbol,
  onPrice,
}: {
  symbol: string
  onPrice: (paise: number) => void
}) {
  const book = useAppSelector((s) => s.market.books[symbol])
  const asks = levels(book, 'SELL', 10).reverse()
  const bids = levels(book, 'BUY', 10)
  const max = Math.max(1, ...asks.map((l) => l[1]), ...bids.map((l) => l[1]))
  const bestBid = bids[0]?.[0]
  const bestAsk = asks[asks.length - 1]?.[0]
  const spread = bestBid && bestAsk ? bestAsk - bestBid : null
  const row = (side: 'bid' | 'ask') =>
    function Row([price, quantity, orders]: readonly [number, number, number]) {
      return (
        <tr
          key={`${side}-${price}`}
          className={side}
          onClick={() => onPrice(price)}
        >
          <td className="orders">{orders}</td>
          <td className="qty">
            <span
              className="bar"
              style={{ width: `${(quantity / max) * 100}%` }}
            />
            <span>{quantity.toLocaleString('en-IN')}</span>
          </td>
          <td className="px">{rupees(price)}</td>
        </tr>
      )
    }
  return (
    <section className="panel ladder">
      <div className="panel-title">
        Order book{' '}
        {book?.stale ? <span className="warn">resyncing</span> : null}
      </div>
      <table>
        <thead>
          <tr>
            <th>orders</th>
            <th>quantity</th>
            <th>price</th>
          </tr>
        </thead>
        <tbody>{asks.map(row('ask'))}</tbody>
        <tbody>
          <tr className="spread">
            <td colSpan={3}>
              {spread !== null
                ? `spread ${rupees(spread)}`
                : 'no two-sided market'}
            </td>
          </tr>
        </tbody>
        <tbody>{bids.map(row('bid'))}</tbody>
      </table>
    </section>
  )
}
