import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router'
import { adminApi, opsApi, postTradeApi } from '../api/rest'
import type { OpsStatus, PostTradeStatus } from '../api/types'
import { withToken, signIn } from '../auth/auth'
import { rolesOf } from '../auth/roles'
import { useAuth } from '../auth/useAuth'
import { useAccountFeed } from '../account/useAccountFeed'
import { MarketControl, SimulationPanel } from '../admin/ControlPanels'
import { usePoll } from '../admin/useAdmin'
import { Header } from '../components/Header'
import { LeaderboardPanel } from '../components/LeaderboardPanel'
import { useAppSelector } from '../hooks'
import { rupees, simClock } from '../market/format'
import { dayProgress, wallSecondsForDay } from './simDay'

/**
 * The ops page (S19): run the market. Session and clock, the simulated traders, a whole simulated day at speed, and
 * the health of everything after the trade (Kafka, post-trade, snapshots). For the ops and admin roles.
 */
export function OpsPage() {
  const { token } = useAuth()
  const roles = rolesOf(token)
  if (!token) {
    return (
      <div className="app">
        <Header />
        <p className="pad">
          The ops page needs an ops sign-in.{' '}
          <button onClick={() => void signIn()}>Sign in</button>
        </p>
      </div>
    )
  }
  if (!roles.includes('ops') && !roles.includes('admin')) {
    return (
      <div className="app">
        <Header />
        <p className="pad">
          Your account has no ops role. <Link to="/">Back to the market</Link>
        </p>
      </div>
    )
  }
  return <OpsConsole />
}

function OpsConsole() {
  const { token } = useAuth()
  useAccountFeed(token) // who you are, so the leaderboard can mark your row
  const status = usePoll(opsApi.status, 1000)
  const simulation = usePoll(opsApi.simulation, 3000)
  const ledger = usePoll(postTradeApi.status, 3000)
  const symbols = useAppSelector((s) => Object.keys(s.market.books).sort())
  return (
    <div className="app">
      <Header />
      <main className="admin-grid">
        <RunADay
          status={status.value}
          ledger={ledger.value}
          onChange={status.refresh}
        />
        <MarketControl status={status.value} onChange={status.refresh} />
        <SimulationPanel
          simulation={simulation.value}
          symbols={
            symbols.length ? symbols : ['HDFCBANK', 'INFY', 'RELIANCE', 'TCS']
          }
          onChange={simulation.refresh}
        />
        <AfterTheTrade
          status={status.value}
          ledger={ledger.value}
          ledgerError={ledger.error}
        />
        <LeaderboardPanel limit={20} />
      </main>
    </div>
  )
}

const DAY_SPEED = 300

/**
 * Runs one full simulated trading day: jumps to the next open if the market is closed, speeds the clock up, and once
 * the close has passed puts the clock back to real time and reports what the day did.
 */
function RunADay({
  status,
  ledger,
  onChange,
}: {
  status: OpsStatus | null
  ledger: PostTradeStatus | null
  onChange: () => void
}) {
  const [running, setRunning] = useState(false)
  const [report, setReport] = useState<string | null>(null)
  const [started, setStarted] = useState<{
    trades: number
    wall: number
  } | null>(null)
  const progress = status ? dayProgress(status.simTime) : null

  const start = useCallback(async () => {
    setReport(null)
    await withToken(async (t) => {
      if (status?.session !== 'OPEN') await adminApi.nextOpen(t)
      await adminApi.clock(t, DAY_SPEED)
    })
    setStarted({ trades: ledger?.trades ?? 0, wall: Date.now() })
    setRunning(true)
    onChange()
  }, [status?.session, ledger?.trades, onChange])

  const stop = useCallback(
    async (finished: boolean) => {
      await withToken((t) => adminApi.clock(t, 1))
      setRunning(false)
      if (finished && started) {
        const seconds = Math.round((Date.now() - started.wall) / 1000)
        const trades = ledger ? ledger.trades - started.trades : 0
        setReport(
          `Day complete in ${seconds} s of wall time: ${trades.toLocaleString('en-IN')} trades booked by post-trade.`,
        )
      }
    },
    [started, ledger],
  )

  // The close has passed (the engine expired the day's orders): back to real time. Scheduled rather than called
  // here, so the state changes happen in a callback, not during the effect.
  const dayOver =
    running && status?.session === 'CLOSED' && progress?.phase === 'after'
  useEffect(() => {
    if (!dayOver) return
    const timer = setTimeout(() => void stop(true), 0)
    return () => clearTimeout(timer)
  }, [dayOver, stop])

  const trades = ledger && started ? ledger.trades - started.trades : null
  return (
    <section className="panel">
      <div className="panel-title">Run a simulated day</div>
      <p className="muted">
        Sim time <b>{status ? simClock(status.simTime) : '--'}</b> IST · session{' '}
        <b className={`session-text ${status?.session.toLowerCase() ?? ''}`}>
          {status?.session ?? '...'}
        </b>{' '}
        · speed {status?.clockMultiplier ?? '-'}x
      </p>
      <div className="progress" aria-label="trading day progress">
        <div
          className="progress-bar"
          style={{ width: `${Math.round((progress?.fraction ?? 0) * 100)}%` }}
        />
      </div>
      <p className="muted small-print">
        09:15 open → 15:30 close
        {progress?.phase === 'during' &&
          ` · ${progress.minutesToClose} sim minutes to the close`}
        {running &&
          trades !== null &&
          ` · ${trades.toLocaleString('en-IN')} trades so far`}
      </p>
      <div className="button-row">
        {!running ? (
          <button onClick={() => void start()} disabled={!status}>
            Run a day at {DAY_SPEED}x (about {wallSecondsForDay(DAY_SPEED)} s)
          </button>
        ) : (
          <button onClick={() => void stop(false)}>
            Stop: back to real time
          </button>
        )}
      </div>
      {report && <p className="result ok">{report}</p>}
    </section>
  )
}

function AfterTheTrade({
  status,
  ledger,
  ledgerError,
}: {
  status: OpsStatus | null
  ledger: PostTradeStatus | null
  ledgerError: string | null
}) {
  const [snapshot, setSnapshot] = useState<string | null>(null)
  const kafka = status?.kafka
  const consumed = ledger
    ? Math.max(0, ...Object.values(ledger.lastEventIdByPartition))
    : 0
  const zeroSum =
    ledger !== null &&
    ledger.pnlBeforeCharges === 0 &&
    Object.values(ledger.netQuantityBySymbol).every((q) => q === 0)
  return (
    <section className="panel">
      <div className="panel-title">After the trade</div>
      <div className="stat-grid">
        <div className="stat">
          <span className="muted">Kafka publisher</span>
          <b className={kafka?.connected ? 'up' : 'down'}>
            {!kafka?.enabled ? 'off' : kafka.connected ? 'connected' : 'down'}
          </b>
        </div>
        <div className="stat">
          <span className="muted">Kafka lag</span>
          <b>{kafka ? kafka.lag.toLocaleString('en-IN') : '-'} events</b>
        </div>
        <div className="stat">
          <span className="muted">Ledger consumed</span>
          <b>{consumed.toLocaleString('en-IN')}</b>
        </div>
        <div className="stat">
          <span className="muted">Ledger zero-sum</span>
          <b className={zeroSum ? 'up' : 'down'}>
            {ledger ? (zeroSum ? 'yes' : 'NO') : '-'}
          </b>
        </div>
        <div className="stat">
          <span className="muted">Trades / accounts</span>
          <b>
            {ledger
              ? `${ledger.trades.toLocaleString('en-IN')} / ${ledger.accounts}`
              : '-'}
          </b>
        </div>
        <div className="stat">
          <span className="muted">Charges collected</span>
          <b>{ledger ? `₹${rupees(ledger.charges)}` : '-'}</b>
        </div>
        <div className="stat">
          <span className="muted">Last snapshot</span>
          <b>
            {status?.lastSnapshotInputSeq
              ? `seq ${status.lastSnapshotInputSeq.toLocaleString('en-IN')}`
              : 'none since this start'}
          </b>
        </div>
        <div className="stat">
          <span className="muted">Started from snapshot</span>
          <b>
            {status?.recoveredFromSnapshotInputSeq
              ? `seq ${status.recoveredFromSnapshotInputSeq.toLocaleString('en-IN')}`
              : 'no'}
          </b>
        </div>
      </div>
      {ledgerError && <p className="result bad">Post-trade: {ledgerError}</p>}
      <div className="button-row">
        <button
          onClick={() =>
            void withToken(opsApi.snapshot)
              .then((r) =>
                setSnapshot(`Snapshot taken at input seq ${r.inputSeq}`),
              )
              .catch((e: Error) => setSnapshot(`Snapshot failed: ${e.message}`))
          }
        >
          Take a snapshot now
        </button>
      </div>
      {snapshot && <p className="result ok">{snapshot}</p>}
    </section>
  )
}
