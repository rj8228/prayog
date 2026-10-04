import { useEffect, useState } from 'react'
import { api } from '../api/rest'
import type { Fill, Me, OpenOrder } from '../api/types'
import { withToken } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { startPrivateFeed } from '../market/feed'
import { orderLatencyMs } from '../market/latency'
import { JourneyDialog } from './JourneyDialog'
import { rupees, simClock } from '../market/format'

/** The signed-in account: open orders (cancel buttons) and fills, live from the private feed. */
export function MyOrders({ refresh }: { refresh: number }) {
  const { token } = useAuth()
  const [orders, setOrders] = useState<OpenOrder[]>([])
  const [fills, setFills] = useState<Fill[]>([])
  const [me, setMe] = useState<Me | null>(null)
  const [journey, setJourney] = useState<number | null>(null)

  // Bumped by the private feed and by cancels: refetch open orders.
  const [version, setVersion] = useState(0)
  const reload = () => setVersion((v) => v + 1)

  useEffect(() => {
    if (!token) return
    void withToken(api.me).then(setMe)
    return startPrivateFeed(token, (m) => {
      const message = m as { type: string }
      if (message.type === 'fill')
        setFills((f) => [m as Fill, ...f].slice(0, 50))
      if (message.type === 'fill' || message.type === 'order')
        setVersion((v) => v + 1)
    })
  }, [token])

  useEffect(() => {
    if (!token) return
    let active = true
    const fetchOrders = () =>
      withToken(api.openOrders)
        .then((o) => {
          if (active) setOrders(o)
        })
        .catch(() => undefined) // a lost session shows up as the sign-in prompt
    void fetchOrders()
    const timer = setInterval(() => void fetchOrders(), 5000)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [token, refresh, version])

  if (!token) return null
  return (
    <section className="panel mine">
      <div className="panel-title">
        My orders{' '}
        {me && <span className="muted">account {me.accountLabel}</span>}
        {orders.length > 0 && (
          <button
            className="small"
            onClick={() => void withToken(api.cancelAll).then(reload)}
          >
            Cancel all
          </button>
        )}
      </div>
      {orders.length === 0 ? (
        <p className="muted">No open orders.</p>
      ) : (
        <table>
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
                        reload,
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
      <div className="panel-title">My fills</div>
      {fills.length === 0 ? (
        <p className="muted">No fills yet in this session.</p>
      ) : (
        <table>
          <tbody>
            {fills.map((f) => (
              <tr
                key={`${f.tradeId}-${f.orderId}`}
                className="clickable"
                onClick={() => setJourney(f.orderId)}
              >
                <td className="muted">{simClock(f.simTime)}</td>
                <td>{f.symbol}</td>
                <td className={f.side === 'BUY' ? 'up' : 'down'}>{f.side}</td>
                <td>{f.quantity}</td>
                <td className="px">{rupees(f.price)}</td>
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
