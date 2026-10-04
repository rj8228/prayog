import {
  ColorType,
  createChart,
  LineSeries,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { useEffect, useMemo, useRef, useState } from 'react'
import { adminApi } from '../api/rest'
import type { ReplayWindow } from '../api/types'
import { withToken } from '../auth/auth'
import { rupees, simClock } from '../market/format'
import { bookAt } from './replayBook'

/** Steps through the last minutes of one symbol, rebuilt by the exchange from its journal. */
export function ReplayViewer({ symbols }: { symbols: string[] }) {
  const [symbol, setSymbol] = useState('INFY')
  const [minutes, setMinutes] = useState(10)
  const [window, setWindow] = useState<ReplayWindow | null>(null)
  const [index, setIndex] = useState(0)
  const [playing, setPlaying] = useState(false)
  const [speed, setSpeed] = useState(10)
  const [error, setError] = useState<string | null>(null)
  const chartBox = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const line = useRef<ISeriesApi<'Line'> | null>(null)

  const load = async () => {
    setError(null)
    setPlaying(false)
    try {
      const w = await withToken((t) => adminApi.replay(t, symbol, minutes))
      setWindow(w)
      setIndex(0)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  // Play: advance one frame at a time, waiting the sim-time gap divided by the speed (capped at 1 s).
  useEffect(() => {
    if (!playing || !window || index >= window.frames.length) return
    const now = window.frames[index].simTime
    const prev = index > 0 ? window.frames[index - 1].simTime : now
    const delay = Math.min(1000, (now - prev) / 1000 / speed)
    const timer = setTimeout(() => setIndex((i) => i + 1), delay)
    return () => clearTimeout(timer)
  }, [playing, window, index, speed])

  const book = useMemo(
    () => (window ? bookAt(window, index) : null),
    [window, index],
  )
  const trades = useMemo(
    () =>
      window
        ? window.frames
            .slice(0, index)
            .flatMap((f) => f.trades.map((t) => ({ ...t, simTime: f.simTime })))
        : [],
    [window, index],
  )

  useEffect(() => {
    if (!chartBox.current) return
    const c = createChart(chartBox.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#9aa4b2',
      },
      grid: {
        vertLines: { color: '#1f2633' },
        horzLines: { color: '#1f2633' },
      },
      timeScale: { timeVisible: true, secondsVisible: true },
      localization: { priceFormatter: (p: number) => (p / 100).toFixed(2) },
    })
    line.current = c.addSeries(LineSeries, { color: '#5b9cf5', lineWidth: 2 })
    chart.current = c
    return () => c.remove()
  }, [])

  useEffect(() => {
    // One point per second (the last trade in it), shown in IST.
    const bySecond = new Map<number, number>()
    for (const t of trades)
      bySecond.set(Math.floor(t.simTime / 1_000_000) + 5.5 * 3600, t.price)
    line.current?.setData(
      [...bySecond.entries()].map(([time, value]) => ({
        time: time as UTCTimestamp,
        value,
      })),
    )
  }, [trades])

  const asks = book
    ? [...book.asks.entries()]
        .sort((a, b) => a[0] - b[0])
        .slice(0, 8)
        .reverse()
    : []
  const bids = book
    ? [...book.bids.entries()].sort((a, b) => b[0] - a[0]).slice(0, 8)
    : []
  const current =
    window && index > 0 ? window.frames[index - 1].simTime : window?.fromSimTime

  return (
    <section className="panel">
      <div className="panel-title">Event replay</div>
      <div className="button-row">
        <select value={symbol} onChange={(e) => setSymbol(e.target.value)}>
          {symbols.map((s) => (
            <option key={s}>{s}</option>
          ))}
        </select>
        <select
          value={minutes}
          onChange={(e) => setMinutes(Number(e.target.value))}
        >
          {[5, 10, 30, 60].map((m) => (
            <option key={m} value={m}>
              last {m} min
            </option>
          ))}
        </select>
        <button className="primary" onClick={() => void load()}>
          Load from journal
        </button>
      </div>
      {error && <p className="result bad">{error}</p>}
      {window && (
        <>
          <div className="button-row">
            <button
              onClick={() => setPlaying((p) => !p)}
              disabled={index >= window.frames.length}
            >
              {playing ? 'Pause' : 'Play'}
            </button>
            <button onClick={() => setIndex(0)}>⟲</button>
            {[1, 10, 60].map((s) => (
              <button
                key={s}
                className={speed === s ? 'selected' : ''}
                onClick={() => setSpeed(s)}
              >
                {s}x
              </button>
            ))}
            <span className="muted">
              {current ? simClock(current) : '--'} IST · frame {index}/
              {window.frames.length}
              {window.truncated ? ' (truncated)' : ''}
            </span>
          </div>
          <input
            type="range"
            min={0}
            max={window.frames.length}
            value={index}
            onChange={(e) => setIndex(Number(e.target.value))}
            style={{ width: '100%' }}
            aria-label="Replay position"
          />
          <div className="replay-grid">
            <table className="compact">
              <tbody>
                {asks.map(([p, q]) => (
                  <tr key={`a${p}`} className="ask">
                    <td>{q}</td>
                    <td className="down">{rupees(p)}</td>
                  </tr>
                ))}
                <tr>
                  <td colSpan={2} className="muted">
                    ---
                  </td>
                </tr>
                {bids.map(([p, q]) => (
                  <tr key={`b${p}`} className="bid">
                    <td>{q}</td>
                    <td className="up">{rupees(p)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="replay-chart" ref={chartBox} />
          </div>
          <p className="muted small-print">
            {trades.length} trades so far in this window. Rebuilt by replaying
            the input journal through a fresh engine, so what you see is exactly
            what happened.
          </p>
        </>
      )}
      {!window && (
        <div
          className="replay-chart"
          ref={chartBox}
          style={{ display: 'none' }}
        />
      )}
    </section>
  )
}
