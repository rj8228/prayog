import { useState } from 'react'
import { useAuth } from '../auth/useAuth'
import { useAppDispatch, useAppSelector } from '../hooks'
import { rupees } from '../market/format'
import { store } from '../store'
import { useWorkspace } from '../workspace/WorkspaceContext'
import { defaults, STRATEGIES } from './library'
import {
  accountFor,
  isRunning,
  startStrategy,
  stopAll,
  stopStrategy,
} from './manager'
import { DEFAULT_LIMITS, type RiskLimits } from './risk'
import { removed, type StrategyView } from './strategySlice'
import type { ParamValue } from './types'

function PnlSpark({ values }: { values: number[] }) {
  if (values.length < 2) return <svg className="spark" />
  const lo = Math.min(0, ...values)
  const hi = Math.max(0, ...values)
  const y = (v: number) => (hi === lo ? 15 : 28 - ((v - lo) / (hi - lo)) * 26)
  const pts = values
    .map((v, i) => `${(i / (values.length - 1)) * 100},${y(v)}`)
    .join(' ')
  const up = values[values.length - 1] >= 0
  return (
    <svg
      className="spark wide"
      viewBox="0 0 100 30"
      preserveAspectRatio="none"
      aria-hidden="true"
    >
      <line
        x1="0"
        x2="100"
        y1={y(0)}
        y2={y(0)}
        stroke="var(--muted)"
        strokeDasharray="2 2"
        vectorEffect="non-scaling-stroke"
      />
      <polyline
        points={pts}
        fill="none"
        stroke={up ? 'var(--up)' : 'var(--down)'}
        strokeWidth="1.5"
        vectorEffect="non-scaling-stroke"
      />
    </svg>
  )
}

function Card({ s }: { s: StrategyView }) {
  const dispatch = useAppDispatch()
  const [showLog, setShowLog] = useState(false)
  const pnl = Math.round(s.realized + s.unrealized)
  const active = s.status === 'running' || s.status === 'starting'
  return (
    <div className={`strategy-card ${s.status}`}>
      <div className="strategy-head">
        <b>{s.name}</b> · {s.symbol}
        <span className={`badge ${s.status}`}>{s.status}</span>
        <span
          className="muted small-print"
          title="The account this strategy trades in"
        >
          account {s.account}
        </span>
        <span className="spacer" />
        {active ? (
          <button className="small" onClick={() => void stopStrategy(s.id)}>
            Stop
          </button>
        ) : (
          s.status !== 'stopping' && (
            <button className="small" onClick={() => dispatch(removed(s.id))}>
              Remove
            </button>
          )
        )}
      </div>
      <div className="strategy-stats">
        <div>
          <span className="muted">P&L</span>
          <b className={pnl >= 0 ? 'up' : 'down'}>₹{rupees(pnl)}</b>
        </div>
        <PnlSpark values={s.pnlHistory} />
        <div>
          <span className="muted">Position</span>
          <b>{s.position}</b>
        </div>
        <div>
          <span className="muted">Avg</span>
          <b>{s.position ? rupees(Math.round(s.averagePrice)) : '-'}</b>
        </div>
        <div>
          <span className="muted">Orders / fills</span>
          <b>
            {s.orders} / {s.fills}
          </b>
        </div>
        <div>
          <span className="muted">Refused</span>
          <b className={s.rejected ? 'down' : ''}>{s.rejected}</b>
        </div>
      </div>
      {s.reason && <p className="muted small-print">{s.reason}</p>}
      <button className="link" onClick={() => setShowLog((v) => !v)}>
        {showLog ? 'Hide log' : `Show log (${s.log.length})`}
      </button>
      {showLog && (
        <ol className="strategy-log">
          {s.log.map((l, i) => (
            <li key={i} className={l.kind}>
              <span className="muted">
                {new Date(l.t).toLocaleTimeString('en-IN', { hour12: false })}
              </span>{' '}
              {l.text}
            </li>
          ))}
        </ol>
      )}
    </div>
  )
}

/** Run textbook strategies from the browser, each in its own account with its own risk limits. */
export function StrategiesPanel() {
  const { symbol } = useWorkspace()
  const { token } = useAuth()
  const dispatch = useAppDispatch()
  const list = useAppSelector((s) => s.strategies.list)
  const [kindId, setKindId] = useState(STRATEGIES[0].id)
  const kind = STRATEGIES.find((k) => k.id === kindId)!
  const [params, setParams] = useState<Record<string, ParamValue>>(
    defaults(kind),
  )
  const [limits, setLimits] = useState<RiskLimits>(DEFAULT_LIMITS)
  const [error, setError] = useState<string | null>(null)
  const [showForm, setShowForm] = useState(true)
  const busy = isRunning(accountFor(kind.id, symbol))
  const anyActive = list.some((s) => s.status === 'running')

  const pick = (id: string) => {
    const k = STRATEGIES.find((x) => x.id === id)!
    setKindId(id)
    setParams(defaults(k))
    setError(null)
  }

  const start = async () => {
    setError(null)
    try {
      await startStrategy(
        kind.id,
        params,
        limits,
        symbol,
        dispatch,
        store.getState,
      )
      setShowForm(false)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  const limit = (key: keyof RiskLimits, label: string, scale = 1) => (
    <label key={key}>
      {label}
      <input
        inputMode="decimal"
        value={limits[key] / scale}
        onChange={(e) =>
          setLimits({
            ...limits,
            [key]: Math.max(0, Number(e.target.value) || 0) * scale,
          })
        }
      />
    </label>
  )

  return (
    <section className="panel strategies">
      <div className="panel-title">
        Strategies
        {anyActive && (
          <button
            className="small"
            onClick={() => void stopAll('stopped by you (stop all)')}
          >
            Stop all
          </button>
        )}
        {!showForm && token && (
          <button className="small" onClick={() => setShowForm(true)}>
            + New
          </button>
        )}
      </div>
      {!token ? (
        <p className="muted">Sign in to run strategies.</p>
      ) : (
        showForm && (
          <div className="strategy-form">
            <div className="chips" role="radiogroup" aria-label="Strategy">
              {STRATEGIES.map((k) => (
                <button
                  key={k.id}
                  role="radio"
                  aria-checked={k.id === kindId}
                  className={k.id === kindId ? 'selected' : ''}
                  onClick={() => pick(k.id)}
                >
                  {k.name}
                </button>
              ))}
            </div>
            <p className="muted small-print">{kind.summary}</p>
            <div className="param-grid">
              {kind.params.map((p) =>
                p.kind === 'side' ? (
                  <label key={p.key} title={p.help}>
                    {p.label}
                    <select
                      value={String(params[p.key])}
                      onChange={(e) =>
                        setParams({ ...params, [p.key]: e.target.value })
                      }
                    >
                      <option value="BUY">Buy</option>
                      <option value="SELL">Sell</option>
                    </select>
                  </label>
                ) : (
                  <label key={p.key} title={p.help}>
                    {p.label}
                    <input
                      inputMode="decimal"
                      value={String(params[p.key])}
                      onChange={(e) =>
                        setParams({ ...params, [p.key]: e.target.value })
                      }
                    />
                  </label>
                ),
              )}
            </div>
            <details>
              <summary>Risk limits</summary>
              <div className="param-grid">
                {limit('maxPosition', 'Max position (shares)')}
                {limit('maxOrderQuantity', 'Max order (shares)')}
                {limit('maxLoss', 'Stop at loss (₹)', 100)}
                {limit('maxOrdersPerMinute', 'Max orders / minute')}
                {limit('maxDistance', 'Max distance from last (%)', 0.01)}
              </div>
            </details>
            <div className="button-row">
              <button
                className="primary"
                disabled={busy}
                onClick={() => void start()}
              >
                Start {kind.name} on {symbol}
              </button>
              {list.length > 0 && (
                <button onClick={() => setShowForm(false)}>Cancel</button>
              )}
            </div>
            {busy && (
              <p className="muted small-print">
                A {kind.name} on {symbol} is already running.
              </p>
            )}
            {error && <p className="result bad">{error}</p>}
            <p className="muted small-print">
              Runs in this tab once a second; closing the tab cancels its
              orders. Background tabs may run it more slowly.
            </p>
          </div>
        )
      )}
      {list.map((s) => (
        <Card key={s.id} s={s} />
      ))}
    </section>
  )
}
