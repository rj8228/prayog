import { useCallback } from 'react'
import { unrealized } from '../account/positions'
import { postTradeApi } from '../api/rest'
import type { PnlSummary } from '../api/types'
import { useAuth } from '../auth/useAuth'
import { usePoll } from '../admin/useAdmin'
import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'

/**
 * Positions and P&L. The official numbers come from the post-trade ledger (ADR 0015): every fill ever, with charges,
 * and your leaderboard rank. Until post-trade answers, an estimate from this session's fills is shown.
 */
export function Positions() {
  const { token } = useAuth()
  const call = useCallback((t: string) => postTradeApi.pnl(t), [])
  const official = usePoll(call, 2000, token !== null)
  if (token && official.value) {
    return <OfficialPositions pnl={official.value} />
  }
  return <EstimatedPositions />
}

function OfficialPositions({ pnl }: { pnl: PnlSummary }) {
  const rows = pnl.positions.filter(
    (p) => p.quantity !== 0 || p.realisedPnl !== 0,
  )
  return (
    <section className="panel">
      <div className="panel-title">
        Positions & P&L{' '}
        {pnl.rank > 0 && <span className="muted">· rank #{pnl.rank}</span>}
      </div>
      <div className="stat-grid">
        <div className="stat">
          <span className="muted">Net P&L</span>
          <b className={pnl.netPnl >= 0 ? 'up' : 'down'}>
            ₹{rupees(pnl.netPnl)}
          </b>
        </div>
        <div className="stat">
          <span className="muted">Realised</span>
          <b>₹{rupees(pnl.realisedPnl)}</b>
        </div>
        <div className="stat">
          <span className="muted">Unrealised</span>
          <b>₹{rupees(pnl.unrealisedPnl)}</b>
        </div>
        <div className="stat">
          <span className="muted">Charges</span>
          <b>₹{rupees(pnl.charges)}</b>
        </div>
      </div>
      {rows.length === 0 ? (
        <p className="muted">No positions yet.</p>
      ) : (
        <table className="compact">
          <thead>
            <tr>
              <th>symbol</th>
              <th>qty</th>
              <th>avg</th>
              <th>last</th>
              <th>unrealised</th>
              <th>realised</th>
              <th>net</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((p) => (
              <tr key={p.symbol}>
                <td>{p.symbol}</td>
                <td
                  className={
                    p.quantity > 0 ? 'up' : p.quantity < 0 ? 'down' : ''
                  }
                >
                  {p.quantity}
                </td>
                <td className="px">
                  {p.quantity ? rupees(p.averagePrice) : '-'}
                </td>
                <td className="px">{p.mark ? rupees(p.mark) : '-'}</td>
                <td className={p.unrealisedPnl >= 0 ? 'up' : 'down'}>
                  {rupees(p.unrealisedPnl)}
                </td>
                <td className={p.realisedPnl >= 0 ? 'up' : 'down'}>
                  {rupees(p.realisedPnl)}
                </td>
                <td className={p.netPnl >= 0 ? 'up' : 'down'}>
                  {rupees(p.netPnl)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <p className="muted small-print">
        Official, from the post-trade ledger: every fill, average cost, charges
        (brokerage 0.03% up to ₹20 per fill plus 0.0035% fees).
      </p>
    </section>
  )
}

/** Positions and P&L from your fills in this session (average cost), marked at the last trade price. */
function EstimatedPositions() {
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
            Estimated from this session's fills while the post-trade ledger is
            unavailable.
          </p>
        </>
      )}
    </section>
  )
}
