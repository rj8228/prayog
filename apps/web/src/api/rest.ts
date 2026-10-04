// REST calls. The web app talks to /api on its own host (Traefik forwards it to the exchange), so the browser
// needs no cross-origin setup.
import type {
  AccountView,
  Check,
  Instrument,
  Journey,
  Me,
  OpenOrder,
  OrderResult,
  Overview,
  ReplayWindow,
  SessionInfo,
  SessionState,
  Side,
  Simulation,
  TradeMessage,
} from './types'

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

async function call<T>(
  path: string,
  init: RequestInit = {},
  token?: string,
): Promise<T> {
  const headers = new Headers(init.headers)
  if (token) headers.set('Authorization', `Bearer ${token}`)
  if (init.body) headers.set('Content-Type', 'application/json')
  const response = await fetch(`/api/v1${path}`, { ...init, headers })
  if (!response.ok) {
    let message = response.statusText
    try {
      const body = (await response.json()) as { message?: string }
      message = body.message ?? message
    } catch {
      // not JSON
    }
    throw new ApiError(response.status, message)
  }
  return (await response.json()) as T
}

export const api = {
  instruments: () => call<Instrument[]>('/instruments'),
  session: () => call<SessionInfo>('/session'),
  trades: (symbol: string, limit = 2000) =>
    call<TradeMessage[]>(`/market/${symbol}/trades?limit=${limit}`),
  me: (token: string) => call<Me>('/me', {}, token),
  openOrders: (token: string) => call<OpenOrder[]>('/orders', {}, token),
  place: (
    token: string,
    order: {
      symbol: string
      side: Side
      type: 'LIMIT' | 'MARKET'
      price?: number
      quantity: number
    },
  ) =>
    call<OrderResult>(
      '/orders',
      { method: 'POST', body: JSON.stringify(order) },
      token,
    ),
  cancel: (token: string, orderId: number) =>
    call<OrderResult>(`/orders/${orderId}`, { method: 'DELETE' }, token),
  cancelAll: (token: string) =>
    call<{ requested: number; cancelled: number }>(
      '/orders',
      { method: 'DELETE' },
      token,
    ),
  journey: (token: string, orderId: number) =>
    call<Journey>(`/orders/${orderId}/journey`, {}, token),
}

const put = (body: unknown): RequestInit => ({
  method: 'PUT',
  body: JSON.stringify(body),
})
const post = (body?: unknown): RequestInit => ({
  method: 'POST',
  body: body === undefined ? undefined : JSON.stringify(body),
})

/** Admin console calls (role admin; market and clock controls are the ops endpoints). */
export const adminApi = {
  overview: (token: string) => call<Overview>('/admin/overview', {}, token),
  accounts: (token: string) =>
    call<AccountView[]>('/admin/accounts', {}, token),
  cancelAll: (token: string, accountId: number) =>
    call<{ open: number; cancelled: number }>(
      `/admin/accounts/${accountId}/cancel-all`,
      post(),
      token,
    ),
  setEnabled: (token: string, accountId: number, enabled: boolean) =>
    call<{ cancelledOrders: number }>(
      `/ops/accounts/${accountId}`,
      post({ enabled }),
      token,
    ),
  selfTest: (token: string) => call<Check[]>('/admin/selftest', post(), token),
  simulation: (
    token: string,
    change: {
      scenario?: string
      paused?: boolean
      jump?: { symbol: string | null; percent: number }
    },
  ) => call<Simulation>('/admin/simulation', put(change), token),
  session: (token: string, state: SessionState) =>
    call<unknown>('/ops/session', post({ state }), token),
  clock: (token: string, multiplier: number) =>
    call<unknown>('/ops/clock', put({ multiplier }), token),
  nextOpen: (token: string) =>
    call<unknown>('/ops/clock/next-open', post(), token),
  replay: (token: string, symbol: string, minutes: number) =>
    call<ReplayWindow>(
      `/admin/replay?symbol=${symbol}&minutes=${minutes}&maxFrames=20000`,
      {},
      token,
    ),
}
