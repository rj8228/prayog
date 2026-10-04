import { useAppSelector } from '../hooks'
import { changePercent, rupees } from '../market/format'

export function TickerBar({
  selected,
  onSelect,
}: {
  selected: string
  onSelect: (s: string) => void
}) {
  const books = useAppSelector((s) => s.market.books)
  const symbols = Object.keys(books).sort()
  return (
    <nav className="tickers">
      {symbols.map((symbol) => {
        const t = books[symbol].ticker
        const last = t?.last || t?.referencePrice || 0
        const change = changePercent(t?.last ?? 0, t?.referencePrice ?? 0)
        return (
          <button
            key={symbol}
            className={`ticker ${symbol === selected ? 'selected' : ''}`}
            onClick={() => onSelect(symbol)}
          >
            <span className="symbol">{symbol}</span>
            <span className="price">{rupees(last)}</span>
            <span className={change.startsWith('-') ? 'down' : 'up'}>
              {change}
            </span>
          </button>
        )
      })}
    </nav>
  )
}
