import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'
import { depthView, type Step } from '../market/depth'
import { levels } from '../market/marketSlice'
import { useWorkspace } from '../workspace/WorkspaceContext'

/** Cumulative quantity by price: how much you could buy (asks, right) or sell (bids, left) up to each price. */
export function DepthChart() {
  const { symbol, setPrice } = useWorkspace()
  const book = useAppSelector((s) => s.market.books[symbol])
  const bids = levels(book, 'BUY', 40)
  const asks = levels(book, 'SELL', 40)
  const view = depthView(bids, asks)
  if (!view) {
    return (
      <section className="panel">
        <div className="panel-title">Depth chart · {symbol}</div>
        <p className="muted">Waiting for a two-sided book.</p>
      </section>
    )
  }
  const { bids: bidSteps, asks: askSteps, lo, hi, mid, top } = view
  const W = 400
  const H = 160
  const x = (p: number) => ((p - lo) / (hi - lo || 1)) * W
  const y = (q: number) => H - (q / top) * (H - 10)
  const area = (steps: readonly Step[], toLeft: boolean) => {
    const pts = steps.map(([p, q]) => `${x(p)},${y(q)}`)
    const edge = toLeft ? 0 : W
    return `M${x(steps[0][0])},${H} L${pts.join(' L')} L${edge},${y(steps[steps.length - 1][1])} L${edge},${H} Z`
  }
  return (
    <section className="panel">
      <div className="panel-title">Depth chart · {symbol}</div>
      <svg
        className="depth"
        viewBox={`0 0 ${W} ${H + 18}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Depth chart for ${symbol}`}
        onClick={(e) => {
          const box = e.currentTarget.getBoundingClientRect()
          const price = lo + ((e.clientX - box.left) / box.width) * (hi - lo)
          setPrice(Math.round(price / 5) * 5)
        }}
      >
        <path
          d={area(bidSteps, true)}
          fill="var(--up)"
          fillOpacity="0.25"
          stroke="var(--up)"
        />
        <path
          d={area(askSteps, false)}
          fill="var(--down)"
          fillOpacity="0.25"
          stroke="var(--down)"
        />
        <line
          x1={x(mid)}
          x2={x(mid)}
          y1={0}
          y2={H}
          stroke="var(--muted)"
          strokeDasharray="3 3"
        />
        <text x={2} y={H + 14} className="axis">
          {rupees(Math.round(lo))}
        </text>
        <text x={x(mid)} y={H + 14} textAnchor="middle" className="axis">
          {rupees(Math.round(mid))}
        </text>
        <text x={W - 2} y={H + 14} textAnchor="end" className="axis">
          {rupees(Math.round(hi))}
        </text>
      </svg>
      <p className="muted small-print">
        Bid depth {bidSteps[bidSteps.length - 1][1].toLocaleString('en-IN')} ·
        ask depth {askSteps[askSteps.length - 1][1].toLocaleString('en-IN')}{' '}
        shares within the window shown. Click to pick a price.
      </p>
    </section>
  )
}
