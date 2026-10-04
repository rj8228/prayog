import { useEffect, useState } from 'react'
import { api } from '../api/rest'
import type { Journey } from '../api/types'
import { withToken } from '../auth/auth'
import { rupees, simClock } from '../market/format'

/** The life of one order, from the exchange's journal: accepted, fills (as maker or taker), changes, cancel. */
export function JourneyDialog({
  orderId,
  latencyMs,
  onClose,
}: {
  orderId: number
  latencyMs?: number
  onClose: () => void
}) {
  const [journey, setJourney] = useState<Journey | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    withToken((t) => api.journey(t, orderId))
      .then((j) => active && setJourney(j))
      .catch((e: Error) => active && setError(e.message))
    return () => {
      active = false
    }
  }, [orderId])
  const show = (k: string, v: string | number) =>
    k === 'price' ? rupees(Number(v)) : String(v)
  return (
    <div className="dialog-backdrop" onClick={onClose}>
      <div
        className="dialog panel"
        role="dialog"
        aria-label={`Order ${orderId}`}
        onClick={(e) => e.stopPropagation()}
      >
        <div className="panel-title">
          Order {orderId}
          <button className="small" onClick={onClose}>
            Close
          </button>
        </div>
        {latencyMs !== undefined && (
          <p className="muted">
            Your round trip when placing it: <b>{latencyMs} ms</b> (browser →
            exchange → journal on disk → answer).
          </p>
        )}
        {error && <p className="result bad">{error}</p>}
        {journey && (
          <ol className="timeline">
            {journey.steps.map((s) => (
              <li key={`${s.eventSeq}-${s.event}`}>
                <span className="muted">
                  {simClock(s.simTime)} · event #{s.eventSeq}
                </span>
                <b>{s.event}</b>
                <span>
                  {Object.entries(s.detail)
                    .map(([k, v]) => `${k} ${show(k, v)}`)
                    .join(' · ')}
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </div>
  )
}
