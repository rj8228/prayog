// Pre-trade risk checks for strategies, run on every order before it is sent, plus a loss limit that stops the
// strategy. The exchange has its own checks (price bands, kill switch); these protect you from your own algorithm.
import type { Action, Context } from './types'
import { openOn } from './math'

export interface RiskLimits {
  maxPosition: number // shares, either way, counting open orders as if they filled
  maxOrderQuantity: number
  maxLoss: number // paise: stop the strategy when P&L falls below -maxLoss
  maxOrdersPerMinute: number
  maxDistance: number // fraction: a price further than this from the last trade is refused (fat finger)
}

export const DEFAULT_LIMITS: RiskLimits = {
  maxPosition: 200,
  maxOrderQuantity: 100,
  maxLoss: 2_000_00, // ₹2,000
  maxOrdersPerMinute: 60,
  maxDistance: 0.02,
}

export type Verdict = { ok: true } | { ok: false; reason: string }

/**
 * Checks one action against the limits. `sentAt` holds the times (ms) of orders sent recently. Cancels are always
 * allowed: they only reduce risk.
 */
export function vet(
  action: Action,
  ctx: Context,
  limits: RiskLimits,
  sentAt: number[],
): Verdict {
  if (action.type !== 'place') return { ok: true }
  if (!Number.isInteger(action.quantity) || action.quantity <= 0)
    return { ok: false, reason: 'quantity must be positive' }
  if (action.quantity > limits.maxOrderQuantity)
    return {
      ok: false,
      reason: `order of ${action.quantity} is above the ${limits.maxOrderQuantity} share limit`,
    }
  if (action.price <= 0 || action.price % ctx.tick !== 0)
    return { ok: false, reason: `price ${action.price} is not on the tick` }
  const reference = ctx.last || (ctx.bestBid + ctx.bestAsk) / 2
  if (
    reference &&
    Math.abs(action.price - reference) / reference > limits.maxDistance
  )
    return {
      ok: false,
      reason: `price is more than ${limits.maxDistance * 100}% from the last trade`,
    }
  // Worst case: every open order on this side fills, and this one too.
  const signed = action.side === 'BUY' ? 1 : -1
  const worst =
    ctx.position + signed * (openOn(ctx, action.side) + action.quantity)
  if (Math.abs(worst) > limits.maxPosition)
    return {
      ok: false,
      reason: `could reach a position of ${worst}, limit ${limits.maxPosition}`,
    }
  const recent = sentAt.filter((t) => ctx.now - t < 60_000).length
  if (recent >= limits.maxOrdersPerMinute)
    return { ok: false, reason: `${recent} orders in the last minute` }
  return { ok: true }
}

/** True when the loss limit is hit and the strategy must stop (after cancelling its orders). */
export function lossLimitHit(pnl: number, limits: RiskLimits): boolean {
  return pnl <= -limits.maxLoss
}
