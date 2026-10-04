import { useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { useAppSelector } from '../hooks'
import { rupees, simClock } from '../market/format'
import { orderLatencyMs } from '../market/latency'
import { JourneyDialog } from './JourneyDialog'

export function MyFills() {
  const { token } = useAuth()
  const fills = useAppSelector((s) => s.account.fills)
  const [journey, setJourney] = useState<number | null>(null)
  return (
    <section className="panel">
      <div className="panel-title">My fills</div>
      {!token ? (
        <p className="muted">Sign in to see your fills.</p>
      ) : fills.length === 0 ? (
        <p className="muted">No fills yet in this session.</p>
      ) : (
        <table className="compact">
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
                <td className="muted">{f.aggressor ? 'taker' : 'maker'}</td>
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
