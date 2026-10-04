import { useAppSelector } from '../hooks'
import { changePercent, rupees } from '../market/format'
import { useWorkspace } from '../workspace/WorkspaceContext'

function Sparkline({ prices }: { prices: number[] }) {
  if (prices.length < 2) return <svg className="spark" />
  const lo = Math.min(...prices)
  const hi = Math.max(...prices)
  const points = prices
    .map(
      (p, i) =>
        `${(i / (prices.length - 1)) * 100},${hi === lo ? 15 : 28 - ((p - lo) / (hi - lo)) * 26}`,
    )
    .join(' ')
  const up = prices[prices.length - 1] >= prices[0]
  return (
    <svg
      className="spark"
      viewBox="0 0 100 30"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <polyline
        points={points}
        fill="none"
        stroke={up ? 'var(--up)' : 'var(--down)'}
        strokeWidth="1.5"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}

export function Watchlist() {
  const { symbol: selected, setSymbol } = useWorkspace()
  const books = useAppSelector((s) => s.market.books)
  return (
    <section className="panel">
      <div className="panel-title">Watchlist</div>
      <table className="compact watch">
        <thead>
          <tr>
            <th>symbol</th>
            <th>last</th>
            <th>change</th>
            <th>spread</th>
            <th>recent</th>
          </tr>
        </thead>
        <tbody>
          {Object.keys(books)
            .sort()
            .map((symbol) => {
              const b = books[symbol]
              const t = b.ticker
              const change = changePercent(t?.last ?? 0, t?.referencePrice ?? 0)
              const spread =
                t?.bestBid && t?.bestAsk ? rupees(t.bestAsk - t.bestBid) : '-'
              return (
                <tr
                  key={symbol}
                  className={`clickable ${symbol === selected ? 'selected-row' : ''}`}
                  onClick={() => setSymbol(symbol)}
                >
                  <td>
                    <b>{symbol}</b>
                  </td>
                  <td className="px">{t?.last ? rupees(t.last) : '-'}</td>
                  <td className={change.startsWith('-') ? 'down' : 'up'}>
                    {change}
                  </td>
                  <td className="muted">{spread}</td>
                  <td>
                    <Sparkline
                      prices={b.trades.slice(-80).map((x) => x.price)}
                    />
                  </td>
                </tr>
              )
            })}
        </tbody>
      </table>
    </section>
  )
}
