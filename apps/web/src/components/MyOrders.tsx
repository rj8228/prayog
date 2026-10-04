import { useState } from 'react'
import { api } from '../api/rest'
import { withToken } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'
import { orderLatencyMs } from '../market/latency'
import { useWorkspace } from '../workspace/WorkspaceContext'
import { JourneyDialog } from './JourneyDialog'

/** The signed-in account's open orders, as the exchange reports them (kept live by useAccountFeed). */
export function MyOrders() {
  const { token } = useAuth()
  const { placed } = useWorkspace()
  const me = useAppSelector((s) => s.account.me)
  const orders = useAppSelector((s) => s.account.openOrders)
  const [journey, setJourney] = useState<number | null>(null)

  if (!token)
    return (
      <section className="panel">
        <div className="panel-title">My orders</div>
        <p className="muted">Sign in to see your orders.</p>
      </section>
    )
  return (
    <section className="panel mine">
      <div className="panel-title">
        My orders{' '}
        {me && <span className="muted">account {me.accountLabel}</span>}
        {orders.length > 0 && (
          <button
            className="small"
            onClick={() => void withToken(api.cancelAll).then(placed)}
          >
            Cancel all
          </button>
        )}
      </div>
      {orders.length === 0 ? (
        <p className="muted">No open orders.</p>
      ) : (
        <table className="compact">
          <thead>
            <tr>
              <th>id</th>
              <th>symbol</th>
              <th>side</th>
              <th>price</th>
              <th>open / total</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {orders.map((o) => (
              <tr key={o.orderId}>
                <td>
                  <button
                    className="link"
                    title="Show this order's journey"
                    onClick={() => setJourney(o.orderId)}
                  >
                    {o.orderId}
                  </button>
                </td>
                <td>{o.symbol}</td>
                <td className={o.side === 'BUY' ? 'up' : 'down'}>{o.side}</td>
                <td className="px">{rupees(o.price)}</td>
                <td>
                  {o.leavesQuantity} / {o.quantity}
                </td>
                <td>
                  <button
                    className="small"
                    onClick={() =>
                      void withToken((t) => api.cancel(t, o.orderId)).then(
                        placed,
                      )
                    }
                  >
                    Cancel
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {journey !== null && (
        <JourneyDialog
          orderId={journey}
          latencyMs={orderLatencyMs.get(journey)}
          onClose={() => setJourney(null)}
        />
      )}
    </section>
  )
}
