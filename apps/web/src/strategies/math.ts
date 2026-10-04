import type { Context } from './types'

export const roundToTick = (price: number, tick: number) =>
  Math.round(price / tick) * tick

export const mid = (ctx: Context) =>
  ctx.bestBid && ctx.bestAsk
    ? (ctx.bestBid + ctx.bestAsk) / 2
    : ctx.last || ctx.bestBid || ctx.bestAsk

/** Exponential moving average over the prices, `period` samples (alpha = 2 / (period + 1)). */
export function ema(prices: number[], period: number): number {
  if (!prices.length) return 0
  const alpha = 2 / (period + 1)
  let value = prices[0]
  for (let i = 1; i < prices.length; i++) value += alpha * (prices[i] - value)
  return value
}

export function mean(prices: number[]): number {
  return prices.length ? prices.reduce((a, b) => a + b, 0) / prices.length : 0
}

export function stdev(prices: number[]): number {
  if (prices.length < 2) return 0
  const m = mean(prices)
  return Math.sqrt(
    prices.reduce((sum, p) => sum + (p - m) ** 2, 0) / (prices.length - 1),
  )
}

/** Open quantity on one side. */
export const openOn = (ctx: Context, side: 'BUY' | 'SELL') =>
  ctx.openOrders
    .filter((o) => o.side === side)
    .reduce((sum, o) => sum + o.leavesQuantity, 0)
