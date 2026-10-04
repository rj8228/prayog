// Six textbook strategies. Each is small on purpose: read it, change it, learn from it.
import type { Side } from '../api/types'
import { ema, mean, mid, openOn, roundToTick, stdev } from './math'
import type {
  Action,
  Context,
  ParamValue,
  Strategy,
  StrategyKind,
} from './types'

const num = (p: Record<string, ParamValue>, k: string) => Number(p[k])
const paise = (p: Record<string, ParamValue>, k: string) =>
  Math.round(Number(p[k]) * 100)
const sideOf = (p: Record<string, ParamValue>) =>
  (p.side === 'SELL' ? 'SELL' : 'BUY') as Side
const touch = (ctx: Context, side: Side) =>
  side === 'BUY' ? ctx.bestAsk : ctx.bestBid // marketable price

/** Moves the position toward `target` with a marketable limit order; cancels anything resting first. */
function tradeTo(
  ctx: Context,
  target: number,
  maxStep: number,
  why: string,
): Action[] {
  if (ctx.openOrders.length)
    return ctx.openOrders.map((o) => ({
      type: 'cancel',
      orderId: o.orderId,
      why: 'replace',
    }))
  const gap = target - ctx.position
  if (!gap) return []
  const side: Side = gap > 0 ? 'BUY' : 'SELL'
  const price = touch(ctx, side)
  if (!price) return []
  return [
    {
      type: 'place',
      side,
      price,
      quantity: Math.min(Math.abs(gap), maxStep),
      why,
    },
  ]
}

/** TWAP: buy or sell a total evenly over a duration, crossing the spread for each slice. */
export const twap: StrategyKind = {
  id: 'twap',
  name: 'TWAP',
  summary: 'Spread a big order evenly over time so it moves the price less.',
  params: [
    {
      key: 'side',
      label: 'Side',
      kind: 'side',
      default: 'BUY',
      help: 'Buy or sell',
    },
    {
      key: 'total',
      label: 'Total shares',
      kind: 'number',
      default: 200,
      min: 1,
      help: 'Everything to trade',
    },
    {
      key: 'minutes',
      label: 'Over minutes',
      kind: 'number',
      default: 5,
      min: 0.5,
      help: 'Wall-clock minutes',
    },
    {
      key: 'slices',
      label: 'Slices',
      kind: 'number',
      default: 20,
      min: 1,
      help: 'Number of child orders',
    },
  ],
  create(p) {
    const side = sideOf(p)
    const total = num(p, 'total')
    const duration = num(p, 'minutes') * 60_000
    const slices = num(p, 'slices')
    let lastSlice = -1
    let lastAt = 0
    return {
      decide(ctx) {
        const done = Math.abs(ctx.position) // a TWAP starts flat; its position is what it has done
        if (done >= total) return [{ type: 'done', why: `traded all ${total}` }]
        const slice = Math.min(
          slices - 1,
          Math.floor((ctx.now / duration) * slices),
        )
        // After the deadline, whatever is left is re-priced at the touch every 5 s until done.
        const overdue = ctx.now >= duration && ctx.now - lastAt >= 5000
        if (slice === lastSlice && !overdue) return []
        // Each new slice: cancel what is left of the previous one, then send this slice's share of what remains due.
        if (ctx.openOrders.length)
          return ctx.openOrders.map((o) => ({
            type: 'cancel',
            orderId: o.orderId,
            why: 'slice over',
          }))
        const due = Math.ceil((total * (slice + 1)) / slices) - done
        const price = touch(ctx, side)
        if (due <= 0 || !price) return []
        lastSlice = slice
        lastAt = ctx.now
        return [
          {
            type: 'place',
            side,
            price,
            quantity: due,
            why: `slice ${slice + 1}/${slices}`,
          },
        ]
      },
    }
  },
}

/** Iceberg: show only a small part of a large limit order; refill when the visible part trades. */
export const iceberg: StrategyKind = {
  id: 'iceberg',
  name: 'Iceberg',
  summary:
    'Show a small piece of a big order at a time so others cannot see its size.',
  params: [
    {
      key: 'side',
      label: 'Side',
      kind: 'side',
      default: 'BUY',
      help: 'Buy or sell',
    },
    {
      key: 'total',
      label: 'Total shares',
      kind: 'number',
      default: 300,
      min: 1,
      help: 'The whole order',
    },
    {
      key: 'display',
      label: 'Visible shares',
      kind: 'number',
      default: 20,
      min: 1,
      help: 'Shown at a time',
    },
    {
      key: 'price',
      label: 'Limit price ₹ (0 = join best)',
      kind: 'rupees',
      default: 0,
      help: '0 rests at the best bid (buy) or ask (sell)',
    },
  ],
  create(p) {
    const side = sideOf(p)
    const total = num(p, 'total')
    const display = num(p, 'display')
    const limit = paise(p, 'price')
    return {
      decide(ctx) {
        const done = Math.abs(ctx.position)
        if (done >= total) return [{ type: 'done', why: `filled all ${total}` }]
        if (ctx.openOrders.length) return []
        const price = limit || (side === 'BUY' ? ctx.bestBid : ctx.bestAsk)
        if (!price) return []
        const quantity = Math.min(display, total - done)
        return [
          {
            type: 'place',
            side,
            price,
            quantity,
            why: `refill (${done}/${total} done)`,
          },
        ]
      },
    }
  },
}

/** Grid: resting buys below and sells above a centre price, every step; re-placed as they fill. */
export const grid: StrategyKind = {
  id: 'grid',
  name: 'Grid',
  summary:
    'Buy every dip and sell every rise on a fixed price grid; earns in a sideways market.',
  params: [
    {
      key: 'step',
      label: 'Step ₹',
      kind: 'rupees',
      default: 1,
      help: 'Distance between levels',
    },
    {
      key: 'levels',
      label: 'Levels each side',
      kind: 'number',
      default: 3,
      min: 1,
      help: 'Orders above and below',
    },
    {
      key: 'quantity',
      label: 'Shares per level',
      kind: 'number',
      default: 10,
      min: 1,
      help: 'Size of each order',
    },
  ],
  create(p) {
    const levels = num(p, 'levels')
    const quantity = num(p, 'quantity')
    let centre = 0
    return {
      decide(ctx) {
        const step = Math.max(ctx.tick, roundToTick(paise(p, 'step'), ctx.tick))
        if (!centre) centre = roundToTick(mid(ctx), ctx.tick)
        if (!centre) return []
        const actions: Action[] = []
        for (let k = 1; k <= levels; k++) {
          for (const [side, price] of [
            ['BUY', centre - k * step],
            ['SELL', centre + k * step],
          ] as const) {
            const resting = ctx.openOrders.some(
              (o) => o.side === side && o.price === price,
            )
            // A buy level only below the best ask and a sell level only above the best bid, so it rests.
            const rests =
              side === 'BUY'
                ? !ctx.bestAsk || price < ctx.bestAsk
                : !ctx.bestBid || price > ctx.bestBid
            if (!resting && rests)
              actions.push({
                type: 'place',
                side,
                price,
                quantity,
                why: `grid level ${side === 'BUY' ? '-' : '+'}${k}`,
              })
          }
        }
        return actions
      },
    }
  },
}

/** Mean reversion: when the price strays far from its recent average, bet on it coming back. */
export const meanReversion: StrategyKind = {
  id: 'mean-reversion',
  name: 'Mean reversion',
  summary:
    'Buy when the price is unusually low against its recent average, sell when unusually high.',
  params: [
    {
      key: 'lookback',
      label: 'Lookback trades',
      kind: 'number',
      default: 60,
      min: 5,
      help: 'Trades in the average',
    },
    {
      key: 'z',
      label: 'Entry (std devs)',
      kind: 'number',
      default: 1.5,
      min: 0.1,
      help: 'How far is "unusual"',
    },
    {
      key: 'quantity',
      label: 'Position size',
      kind: 'number',
      default: 20,
      min: 1,
      help: 'Shares held when in a trade',
    },
  ],
  create(p) {
    const lookback = num(p, 'lookback')
    const z = num(p, 'z')
    const size = num(p, 'quantity')
    return {
      decide(ctx) {
        const window = ctx.prices.slice(-lookback)
        if (window.length < lookback || !ctx.last) return []
        const m = mean(window)
        const sd = stdev(window)
        if (!sd) return []
        const score = (ctx.last - m) / sd
        let target = ctx.position
        if (score <= -z) target = size
        else if (score >= z) target = -size
        else if (
          (ctx.position > 0 && ctx.last >= m) ||
          (ctx.position < 0 && ctx.last <= m)
        )
          target = 0 // back to mean
        return tradeTo(
          ctx,
          target,
          size,
          `z=${score.toFixed(2)} → target ${target}`,
        )
      },
    }
  },
}

/** Momentum: follow the trend while a fast average is above (or below) a slow one. */
export const momentum: StrategyKind = {
  id: 'momentum',
  name: 'Momentum',
  summary:
    'Go long while the short-term average is above the long-term one, short while below.',
  params: [
    {
      key: 'fast',
      label: 'Fast average (trades)',
      kind: 'number',
      default: 10,
      min: 2,
      help: 'Short EMA period',
    },
    {
      key: 'slow',
      label: 'Slow average (trades)',
      kind: 'number',
      default: 50,
      min: 3,
      help: 'Long EMA period',
    },
    {
      key: 'threshold',
      label: 'Threshold (bps)',
      kind: 'number',
      default: 5,
      min: 0,
      help: 'Gap needed to act, in basis points',
    },
    {
      key: 'quantity',
      label: 'Position size',
      kind: 'number',
      default: 20,
      min: 1,
      help: 'Shares held in a trend',
    },
  ],
  create(p) {
    const fast = num(p, 'fast')
    const slow = num(p, 'slow')
    const bps = num(p, 'threshold') / 10_000
    const size = num(p, 'quantity')
    return {
      decide(ctx) {
        if (ctx.prices.length < slow) return []
        const f = ema(ctx.prices, fast)
        const s = ema(ctx.prices, slow)
        const gap = (f - s) / s
        const target = gap > bps ? size : gap < -bps ? -size : ctx.position
        return tradeTo(
          ctx,
          target,
          size,
          `fast-slow ${(gap * 10_000).toFixed(1)} bps → target ${target}`,
        )
      },
    }
  },
}

/** Market maker: quote a bid and an ask around the mid, leaning against inventory. */
export const marketMaker: StrategyKind = {
  id: 'market-maker',
  name: 'Market maker',
  summary:
    'Quote both sides and earn the spread; lean prices against what you hold so inventory stays small.',
  params: [
    {
      key: 'halfSpread',
      label: 'Half spread (ticks)',
      kind: 'number',
      default: 2,
      min: 1,
      help: 'Distance from the mid',
    },
    {
      key: 'quantity',
      label: 'Quote size',
      kind: 'number',
      default: 10,
      min: 1,
      help: 'Shares on each side',
    },
    {
      key: 'skew',
      label: 'Skew (ticks per 10 shares held)',
      kind: 'number',
      default: 1,
      min: 0,
      help: 'Inventory lean',
    },
    {
      key: 'maxInventory',
      label: 'Max inventory',
      kind: 'number',
      default: 50,
      min: 1,
      help: 'Stops quoting the side that would add more',
    },
  ],
  create(p) {
    const half = num(p, 'halfSpread')
    const size = num(p, 'quantity')
    const skew = num(p, 'skew')
    const max = num(p, 'maxInventory')
    return {
      decide(ctx) {
        const m = mid(ctx)
        if (!m) return []
        const lean = Math.round((ctx.position / 10) * skew) * ctx.tick // long → quote lower to sell more
        let bid = roundToTick(m - half * ctx.tick - lean, ctx.tick)
        let ask = roundToTick(m + half * ctx.tick - lean, ctx.tick)
        // Never cross the market: a quote that would trade at once is moved back to the touch.
        if (ctx.bestAsk && bid >= ctx.bestAsk) bid = ctx.bestAsk - ctx.tick
        if (ctx.bestBid && ask <= ctx.bestBid) ask = ctx.bestBid + ctx.tick
        const want = {
          BUY: ctx.position < max ? bid : 0,
          SELL: ctx.position > -max ? ask : 0,
        }
        const actions: Action[] = []
        for (const side of ['BUY', 'SELL'] as const) {
          const mine = ctx.openOrders.filter((o) => o.side === side)
          for (const o of mine)
            if (o.price !== want[side])
              actions.push({
                type: 'cancel',
                orderId: o.orderId,
                why: `requote ${side.toLowerCase()}`,
              })
          if (
            want[side] &&
            !mine.some((o) => o.price === want[side]) &&
            openOn(ctx, side) === 0
          )
            actions.push({
              type: 'place',
              side,
              price: want[side],
              quantity: size,
              why: `quote ${side === 'BUY' ? 'bid' : 'ask'} (inventory ${ctx.position})`,
            })
        }
        return actions
      },
    }
  },
}

export const STRATEGIES: StrategyKind[] = [
  twap,
  iceberg,
  grid,
  meanReversion,
  momentum,
  marketMaker,
]

export function defaults(kind: StrategyKind): Record<string, ParamValue> {
  return Object.fromEntries(kind.params.map((p) => [p.key, p.default]))
}

export type { Strategy }
