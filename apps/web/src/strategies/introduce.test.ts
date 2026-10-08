import { describe, expect, it, vi } from 'vitest'
import { introduce } from './introduce'

describe('introduce', () => {
  it('looks the account P&L up once, which names it on the leaderboard', async () => {
    const pnl = vi.fn(async () => ({}))
    await introduce('algo-twap-infy', async () => 'tok', pnl)
    expect(pnl).toHaveBeenCalledWith('tok', 'algo-twap-infy')
  })

  it('never fails the strategy when post-trade is down', async () => {
    const pnl = vi.fn(async () => {
      throw new Error('503')
    })
    await expect(
      introduce('algo-twap-infy', async () => 'tok', pnl),
    ).resolves.toBeUndefined()
  })
})
