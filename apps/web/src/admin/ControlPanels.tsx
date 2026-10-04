import { useState } from 'react'
import { adminApi } from '../api/rest'
import type { Overview } from '../api/types'
import { simClock } from '../market/format'
import { useAction } from './useAdmin'

/** Session and clock: the global kill switch (halt) and the sim speed. */
export function MarketControl({
  overview,
  onChange,
}: {
  overview: Overview | null
  onChange: () => void
}) {
  const { status, run } = useAction()
  const [speed, setSpeed] = useState('')
  const act = (label: string, action: (t: string) => Promise<unknown>) =>
    void run(label, action).then(onChange)
  const s = overview?.status
  return (
    <section className="panel">
      <div className="panel-title">Market control</div>
      <p className="muted">
        Session{' '}
        <b className={`session-text ${s?.session.toLowerCase() ?? ''}`}>
          {s?.session ?? '...'}
        </b>{' '}
        · sim time {s ? simClock(s.simTime) : '--'} IST · speed{' '}
        {s?.clockMultiplier ?? '-'}x
      </p>
      <div className="button-row">
        <button onClick={() => act('Open', (t) => adminApi.session(t, 'OPEN'))}>
          Open
        </button>
        <button
          onClick={() => act('Halt', (t) => adminApi.session(t, 'HALTED'))}
        >
          Halt (cancels only)
        </button>
        <button
          onClick={() => act('Close', (t) => adminApi.session(t, 'CLOSED'))}
        >
          Close (expires orders)
        </button>
        <button onClick={() => act('Next open', adminApi.nextOpen)}>
          Skip to next open
        </button>
      </div>
      <div className="button-row">
        <input
          placeholder="speed, e.g. 10"
          inputMode="numeric"
          value={speed}
          onChange={(e) => setSpeed(e.target.value)}
          style={{ maxWidth: 140 }}
        />
        <button
          onClick={() =>
            act(`Speed ${speed}x`, (t) => adminApi.clock(t, Number(speed)))
          }
        >
          Set speed
        </button>
        <button onClick={() => act('Real time', (t) => adminApi.clock(t, 1))}>
          Real time
        </button>
      </div>
      {status && (
        <p className={status.ok ? 'result ok' : 'result bad'}>{status.text}</p>
      )}
    </section>
  )
}

/** Live controls for the simulated traders: scenario, pause, and "news" price jumps. */
export function SimulationPanel({
  overview,
  symbols,
  onChange,
}: {
  overview: Overview | null
  symbols: string[]
  onChange: () => void
}) {
  const { status, run } = useAction()
  const [symbol, setSymbol] = useState<string>('INFY')
  const sim = overview?.simulation
  const change = (
    label: string,
    body: Parameters<typeof adminApi.simulation>[1],
  ) => void run(label, (t) => adminApi.simulation(t, body)).then(onChange)
  return (
    <section className="panel">
      <div className="panel-title">Simulated traders</div>
      <p className="muted">
        Scenario <b>{sim?.scenario ?? 'as started'}</b> ·{' '}
        {sim?.paused ? 'paused' : 'trading'}
      </p>
      <div className="button-row">
        <button
          className={sim?.scenario === 'calm' ? 'selected' : ''}
          onClick={() => change('Calm', { scenario: 'calm' })}
        >
          Calm
        </button>
        <button
          className={sim?.scenario === 'volatile' ? 'selected' : ''}
          onClick={() => change('Volatile', { scenario: 'volatile' })}
        >
          Volatile
        </button>
        <button
          onClick={() =>
            change(sim?.paused ? 'Resume' : 'Pause', { paused: !sim?.paused })
          }
        >
          {sim?.paused ? 'Resume' : 'Pause'}
        </button>
      </div>
      <div className="button-row">
        <select value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          {symbols.map((s) => (
            <option key={s}>{s}</option>
          ))}
          <option value="">all symbols</option>
        </select>
        {[-3, -1, 1, 3].map((p) => (
          <button
            key={p}
            className={p < 0 ? 'down' : 'up'}
            onClick={() =>
              change(`News ${p > 0 ? '+' : ''}${p}%`, {
                jump: { symbol: symbol || null, percent: p },
              })
            }
          >
            News {p > 0 ? '+' : ''}
            {p}%
          </button>
        ))}
      </div>
      <p className="muted small-print">
        A jump moves the hidden fair value; the market maker re-quotes around it
        and the price follows.
      </p>
      {status && (
        <p className={status.ok ? 'result ok' : 'result bad'}>{status.text}</p>
      )}
    </section>
  )
}

export function HealthPanel({
  overview,
  error,
}: {
  overview: Overview | null
  error: string | null
}) {
  if (!overview)
    return <section className="panel">{error ?? 'Loading...'}</section>
  const s = overview.status
  const used = s.ringCapacity - s.ringRemaining
  const orders = Object.entries(overview.orders).sort((a, b) => b[1] - a[1])
  const lat = overview.latencyMillis
  return (
    <section className="panel">
      <div className="panel-title">Health</div>
      <div className="stat-grid">
        <Stat
          label="Input seq"
          value={s.lastProcessedInputSeq.toLocaleString('en-IN')}
        />
        <Stat
          label="Ring in use"
          value={`${used} / ${s.ringCapacity.toLocaleString('en-IN')}`}
          warn={used > s.ringCapacity / 2}
        />
        <Stat
          label="Publish errors"
          value={String(s.publishErrors)}
          warn={s.publishErrors > 0}
        />
        <Stat label="Order latency p50" value={ms(lat['p50.0'])} />
        <Stat label="p99" value={ms(lat['p99.0'])} />
        <Stat label="p99.9" value={ms(lat['p99.9'])} />
        <Stat
          label="Journal"
          value={`${(overview.journalBytes / 1_048_576).toFixed(1)} MB`}
        />
        <Stat
          label="Feed subscribers"
          value={String(overview.marketDataSubscribers)}
        />
        <Stat label="Accounts seen" value={String(overview.accounts)} />
      </div>
      <table className="compact">
        <tbody>
          {orders.map(([k, v]) => (
            <tr key={k}>
              <td>{k.replace('/', ' → ')}</td>
              <td className={k.endsWith('rejected') ? 'down' : ''}>
                {v.toLocaleString('en-IN')}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="muted small-print">
        Recovered from the journal at start-up:{' '}
        {s.recoveredFromJournal ? 'yes' : 'no'}.
      </p>
    </section>
  )
}

function ms(v: number | undefined) {
  return v === undefined ? '-' : `${v.toFixed(v < 10 ? 2 : 0)} ms`
}

function Stat({
  label,
  value,
  warn,
}: {
  label: string
  value: string
  warn?: boolean
}) {
  return (
    <div className={`stat ${warn ? 'warn-box' : ''}`}>
      <span className="muted">{label}</span>
      <b>{value}</b>
    </div>
  )
}
