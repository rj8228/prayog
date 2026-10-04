import { unrealized } from '../account/positions'
import { useAuth } from '../auth/useAuth'
import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'

/** Positions and P&L from your fills in this session (average cost), marked at the last trade price. */
export function Positions() {
  const { token } = useAuth()
  const positions = useAppSelector((s) => s.account.positions)
  const books = useAppSelector((s) => s.market.books)
  const rows = Object.entries(positions).map(([symbol, p]) => {
    const mark = books[symbol]?.ticker?.last ?? 0
    return { symbol, p, mark, open: unrealized(p, mark) }
  })
  const realized = rows.reduce((sum, r) => sum + r.p.realized, 0)
  const open = rows.reduce((sum, r) => sum + r.open, 0)
  const total = Math.round(realized + open)
  return (
    <section className="panel">
      <div className="panel-title">Positions & P&L</div>
      {!token ? (
        <p className="muted">Sign in to see your positions.</p>
      ) : (
        <>
          <div className="stat-grid">
            <div className="stat">
              <span className="muted">Net P&L</span>
              <b className={total >= 0 ? 'up' : 'down'}>₹{rupees(total)}</b>
            </div>
            <div className="stat">
              <span className="muted">Realized</span>
              <b>₹{rupees(Math.round(realized))}</b>
            </div>
            <div className="stat">
              <span className="muted">Unrealized</span>
              <b>₹{rupees(Math.round(open))}</b>
            </div>
          </div>
          {rows.length === 0 ? (
            <p className="muted">
              No positions yet: your fills in this session build them.
            </p>
          ) : (
            <table className="compact">
              <thead>
                <tr>
                  <th>symbol</th>
                  <th>qty</th>
                  <th>avg</th>
                  <th>last</th>
                  <th>unrealized</th>
                  <th>realized</th>
                </tr>
              </thead>
              <tbody>
                {rows.map(({ symbol, p, mark, open: u }) => (
                  <tr key={symbol}>
                    <td>{symbol}</td>
                    <td
                      className={
                        p.quantity > 0 ? 'up' : p.quantity < 0 ? 'down' : ''
                      }
                    >
                      {p.quantity}
                    </td>
                    <td className="px">
                      {p.quantity ? rupees(Math.round(p.averagePrice)) : '-'}
                    </td>
                    <td className="px">{mark ? rupees(mark) : '-'}</td>
                    <td className={u >= 0 ? 'up' : 'down'}>
                      {rupees(Math.round(u))}
                    </td>
                    <td className={p.realized >= 0 ? 'up' : 'down'}>
                      {rupees(Math.round(p.realized))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <p className="muted small-print">
            Estimated from this session's fills; official P&L arrives with the
            post-trade service.
          </p>
        </>
      )}
    </section>
  )
}
