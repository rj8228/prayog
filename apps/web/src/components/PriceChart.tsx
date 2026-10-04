import {
  CandlestickSeries,
  ColorType,
  createChart,
  HistogramSeries,
  type IChartApi,
  type ISeriesApi,
  type UTCTimestamp,
} from 'lightweight-charts'
import { useEffect, useMemo, useRef } from 'react'
import type { TradeMessage } from '../api/types'
import { toCandles } from '../market/candles'

const INTERVALS = [10, 60, 300]

export function PriceChart({
  trades,
  interval,
  onInterval,
}: {
  trades: TradeMessage[]
  interval: number
  onInterval: (s: number) => void
}) {
  const container = useRef<HTMLDivElement>(null)
  const chart = useRef<IChartApi | null>(null)
  const candles = useRef<ISeriesApi<'Candlestick'> | null>(null)
  const volume = useRef<ISeriesApi<'Histogram'> | null>(null)

  useEffect(() => {
    if (!container.current) return
    const c = createChart(container.current, {
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
    candles.current = c.addSeries(CandlestickSeries, {
      upColor: '#26a69a',
      downColor: '#ef5350',
      borderVisible: false,
      wickUpColor: '#26a69a',
      wickDownColor: '#ef5350',
    })
    volume.current = c.addSeries(HistogramSeries, {
      priceScaleId: 'volume',
      color: '#334155',
      lastValueVisible: false, // volume is not a price: keep it off the price axis
      priceLineVisible: false,
    })
    c.priceScale('volume').applyOptions({
      scaleMargins: { top: 0.8, bottom: 0 },
    })
    chart.current = c
    return () => c.remove()
  }, [])

  const data = useMemo(() => toCandles(trades, interval), [trades, interval])

  useEffect(() => {
    // Chart time is shown in IST: shift the UTC seconds by +05:30.
    const shift = 5.5 * 3600
    candles.current?.setData(
      data.map((k) => ({
        time: (k.time + shift) as UTCTimestamp,
        open: k.open,
        high: k.high,
        low: k.low,
        close: k.close,
      })),
    )
    volume.current?.setData(
      data.map((k) => ({
        time: (k.time + shift) as UTCTimestamp,
        value: k.volume,
        color: k.close >= k.open ? '#1d4d4a' : '#5c2b2b',
      })),
    )
  }, [data])

  return (
    <section className="panel chart">
      <div className="panel-title">
        Price
        <span className="intervals">
          {INTERVALS.map((s) => (
            <button
              key={s}
              className={s === interval ? 'selected' : ''}
              onClick={() => onInterval(s)}
            >
              {s < 60 ? `${s}s` : `${s / 60}m`}
            </button>
          ))}
        </span>
      </div>
      <div className="chart-area" ref={container} />
      <div className="attribution">
        Charts by <a href="https://www.tradingview.com/">TradingView</a>
      </div>
    </section>
  )
}
