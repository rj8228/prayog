import { useCallback, useState } from 'react'
import { postTradeApi } from '../api/rest'
import { useAuth } from '../auth/useAuth'
import { usePoll } from '../admin/useAdmin'
import { rupees, simClock } from '../market/format'

type Tab = 'orders' | 'fills' | 'rejections'

/**
 * The blotter: your order history, fills and refused orders from the post-trade ledger (ADR 0015), across sessions
 * and restarts. "My orders" shows what is open right now; this shows everything that happened.
 */
export function Blotter() {
  const { token } = useAuth()
  const [tab, setTab] = useState<Tab>('orders')
  return (
    <section className="panel">
      <div className="panel-title">
        Blotter{' '}
        <span className="tabs">
          {(['orders', 'fills', 'rejections'] as Tab[]).map((t) => (
            <button
              key={t}
              className={tab === t ? 'active' : ''}
              onClick={() => setTab(t)}
            >
              {t}
            </button>
          ))}
        </span>
      </div>
      {!token ? (
        <p className="muted">Sign in to see your history.</p>
      ) : tab === 'orders' ? (
        <Orders />
      ) : tab === 'fills' ? (
        <Fills />
      ) : (
        <Rejections />
      )}
    </section>
  )
}

function Orders() {
  const call = useCallback((t: string) => postTradeApi.orders(t, 200), [])
  const { value, error } = usePoll(call, 2000)
  if (!value) return <p className="muted">{error ?? 'Loading...'}</p>
  if (value.length === 0) return <p className="muted">No orders yet.</p>
  return (
    <table className="compact">
      <thead>
        <tr>
          <th>time</th>
          <th>symbol</th>
          <th>side</th>
          <th>type</th>
          <th>price</th>
          <th>filled</th>
          <th>status</th>
        </tr>
      </thead>
      <tbody>
        {value.map((o) => (
          <tr key={o.orderId}>
            <td className="muted">{simClock(o.createdAt)}</td>
            <td>{o.symbol}</td>
            <td className={o.side === 'BUY' ? 'up' : 'down'}>{o.side}</td>
            <td className="muted">{o.orderType}</td>
            <td className="px">
              {o.orderType === 'MARKET' ? 'MKT' : rupees(o.price)}
            </td>
            <td>
              {o.filledQuantity}/{o.quantity}
            </td>
            <td title={o.reason ?? ''}>
              {o.status.replace('_', ' ')}
              {o.reason ? <span className="muted"> ({o.reason})</span> : null}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Fills() {
  const call = useCallback((t: string) => postTradeApi.fills(t, 200), [])
  const { value, error } = usePoll(call, 2000)
  if (!value) return <p className="muted">{error ?? 'Loading...'}</p>
  if (value.length === 0) return <p className="muted">No fills yet.</p>
  return (
    <table className="compact">
      <thead>
        <tr>
          <th>time</th>
          <th>symbol</th>
          <th>side</th>
          <th>qty</th>
          <th>price</th>
          <th>charges</th>
          <th>realised</th>
        </tr>
      </thead>
      <tbody>
        {value.map((f) => (
          <tr key={`${f.tradeId}-${f.side}`}>
            <td className="muted">{simClock(f.simTime)}</td>
            <td>{f.symbol}</td>
            <td className={f.side === 'BUY' ? 'up' : 'down'}>{f.side}</td>
            <td>{f.quantity}</td>
            <td className="px">{rupees(f.price)}</td>
            <td className="px muted">{rupees(f.charges)}</td>
            <td className={f.realisedPnl >= 0 ? 'px up' : 'px down'}>
              {f.realisedPnl ? rupees(f.realisedPnl) : '-'}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Rejections() {
  const call = useCallback((t: string) => postTradeApi.rejections(t, 100), [])
  const { value, error } = usePoll(call, 5000)
  if (!value) return <p className="muted">{error ?? 'Loading...'}</p>
  if (value.length === 0) return <p className="muted">Nothing refused.</p>
  return (
    <table className="compact">
      <tbody>
        {value.map((r) => (
          <tr key={r.eventId}>
            <td className="muted">{simClock(r.simTime)}</td>
            <td>{r.symbol}</td>
            <td className="down">{r.reason}</td>
            <td className="muted">{r.clientOrderId}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}
