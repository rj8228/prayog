// Starts and stops strategy runners in this tab. Each strategy trades in its own account, named after the strategy
// and symbol (one of each at a time), so its orders and fills never mix with yours.
import { accountApi, api } from '../api/rest'
import type { Fill } from '../api/types'
import { withToken } from '../auth/auth'
import { startAccountFeed } from '../market/feed'
import { levels } from '../market/marketSlice'
import type { AppDispatch, RootState } from '../store'
import { introduce } from './introduce'
import { STRATEGIES } from './library'
import type { RiskLimits } from './risk'
import { Runner, type MarketView } from './runner'
import { added, updated } from './strategySlice'
import type { ParamValue } from './types'

interface Running {
  runner: Runner
  timer: ReturnType<typeof setInterval>
  stopFeed: () => void
  account: string
}

const running = new Map<string, Running>()
const ticks = new Map<string, number>()
let lastToken: string | null = null
let instrumentsLoaded = false

const token = () =>
  withToken(async (t) => {
    lastToken = t
    return t
  })

export function accountFor(kind: string, symbol: string) {
  return `algo-${kind}-${symbol.toLowerCase()}`.slice(0, 32)
}

export function isRunning(account: string) {
  return [...running.values()].some((r) => r.account === account)
}

function marketView(getState: () => RootState, symbol: string): MarketView {
  const book = getState().market.books[symbol]
  const bid = levels(book, 'BUY', 1)[0]?.[0] ?? 0
  const ask = levels(book, 'SELL', 1)[0]?.[0] ?? 0
  const trades = book?.trades ?? []
  return {
    bestBid: bid,
    bestAsk: ask,
    last: book?.ticker?.last ?? trades.at(-1)?.price ?? 0,
    prices: trades.slice(-500).map((t) => t.price),
    tick: ticks.get(symbol) ?? 5,
  }
}

export async function startStrategy(
  kindId: string,
  params: Record<string, ParamValue>,
  limits: RiskLimits,
  symbol: string,
  dispatch: AppDispatch,
  getState: () => RootState,
) {
  const kind = STRATEGIES.find((k) => k.id === kindId)
  if (!kind) throw new Error(`unknown strategy ${kindId}`)
  const account = accountFor(kind.id, symbol)
  if (isRunning(account))
    throw new Error(`a ${kind.name} on ${symbol} is already running`)
  if (!instrumentsLoaded) {
    for (const i of await api.instruments()) ticks.set(i.symbol, i.tickSize)
    instrumentsLoaded = true
  }
  const id = `${account}-${Date.now().toString(36)}`
  const accountCalls = accountApi(account)
  dispatch(
    added({
      id,
      kind: kind.id,
      name: kind.name,
      symbol,
      account,
      params,
      limits,
      status: 'starting',
      reason: null,
      startedAt: Date.now(),
      position: 0,
      averagePrice: 0,
      realized: 0,
      unrealized: 0,
      orders: 0,
      rejected: 0,
      fills: 0,
      pnlHistory: [],
      log: [],
    }),
  )
  // Leftovers from an earlier run of this account (a closed tab) are cancelled first.
  const leftover = await token().then((t) => accountCalls.cancelAll(t))
  void introduce(account, token)
  const runner = new Runner(kind.create(params), symbol, limits, {
    api: accountCalls,
    token,
    market: (s) => marketView(getState, s),
    clock: () => Date.now(),
    report: (patch, log) => dispatch(updated({ id, patch, log })),
  })
  const stopFeed = startAccountFeed(account, token, (m) => {
    const message = m as { type: string }
    if (message.type !== 'fill') return
    const f = m as Fill
    runner.fill(f.tradeId, f.side, f.quantity, f.price, f.symbol)
  })
  const timer = setInterval(() => {
    void runner.tick().then(() => {
      if (runner.isStopped) cleanup(id)
    })
  }, 1000)
  running.set(id, { runner, timer, stopFeed, account })
  dispatch(
    updated({
      id,
      patch: { status: 'running' },
      log: [
        {
          t: Date.now(),
          text: `started in account "${account}"${leftover.cancelled ? `; cancelled ${leftover.cancelled} leftover orders` : ''}`,
          kind: 'info',
        },
      ],
    }),
  )
}

function cleanup(id: string) {
  const r = running.get(id)
  if (!r) return
  clearInterval(r.timer)
  r.stopFeed()
  running.delete(id)
}

export async function stopStrategy(id: string, reason = 'stopped by you') {
  const r = running.get(id)
  if (!r) return
  clearInterval(r.timer)
  await r.runner.stop('stopped', reason)
  cleanup(id)
}

export async function stopAll(reason: string) {
  await Promise.all([...running.keys()].map((id) => stopStrategy(id, reason)))
}

// Closing or reloading the tab: best-effort cancel of every strategy's orders. keepalive lets the request finish
// after the page is gone; it needs a token we already have, as nothing can be awaited here.
if (typeof window !== 'undefined') {
  window.addEventListener('pagehide', () => {
    if (!lastToken) return
    for (const r of running.values())
      void fetch('/api/v1/orders', {
        method: 'DELETE',
        keepalive: true,
        headers: {
          Authorization: `Bearer ${lastToken}`,
          'X-Prayog-Account': r.account,
        },
      }).catch(() => undefined)
  })
}
