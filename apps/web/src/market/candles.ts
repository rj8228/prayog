import type { TradeMessage } from '../api/types'

export interface Candle {
  time: number // bucket start, seconds since the epoch (sim time)
  open: number
  high: number
  low: number
  close: number
  volume: number
}

/** Groups trades (oldest first) into candles of `seconds` of sim time. Prices stay in paise. */
export function toCandles(trades: TradeMessage[], seconds: number): Candle[] {
  const candles: Candle[] = []
  for (const trade of trades) {
    const time = Math.floor(trade.simTime / 1_000_000 / seconds) * seconds
    const last = candles[candles.length - 1]
    if (last && last.time === time) {
      last.high = Math.max(last.high, trade.price)
      last.low = Math.min(last.low, trade.price)
      last.close = trade.price
      last.volume += trade.quantity
    } else if (!last || time > last.time) {
      candles.push({
        time,
        open: trade.price,
        high: trade.price,
        low: trade.price,
        close: trade.price,
        volume: trade.quantity,
      })
    }
  }
  return candles
}
