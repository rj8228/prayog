import { useState } from 'react'
import { adminApi } from '../api/rest'
import type { Check } from '../api/types'
import { withToken } from '../auth/auth'

/** One click runs the exchange's live self-test, including an online replay of the journal. */
export function SelfTestPanel() {
  const [checks, setChecks] = useState<Check[] | null>(null)
  const [running, setRunning] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = async () => {
    setRunning(true)
    setError(null)
    try {
      setChecks(await withToken(adminApi.selfTest))
    } catch (e) {
      setError((e as Error).message)
    } finally {
      setRunning(false)
    }
  }
  const passed = checks?.filter((c) => c.ok).length ?? 0
  return (
    <section className="panel">
      <div className="panel-title">
        Self-test
        <button
          className="primary small"
          disabled={running}
          onClick={() => void run()}
        >
          {running ? 'Running...' : 'Run checks'}
        </button>
      </div>
      {error && <p className="result bad">{error}</p>}
      {checks && (
        <>
          <p className={passed === checks.length ? 'result ok' : 'result bad'}>
            {passed}/{checks.length} checks passed
          </p>
          <table className="compact">
            <tbody>
              {checks.map((c) => (
                <tr key={c.name}>
                  <td className={c.ok ? 'up' : 'down'}>{c.ok ? '✓' : '✗'}</td>
                  <td>
                    {c.name}
                    <div className="muted small-print">{c.detail}</div>
                  </td>
                  <td className="muted">{c.millis} ms</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
      {!checks && !error && (
        <p className="muted">
          Places and cancels a test order, checks the books, the ring and
          publishing, and replays the live journal.
        </p>
      )}
    </section>
  )
}
