// REST calls. The web app talks to /api on its own host (Traefik forwards it to the exchange), so the browser
// needs no cross-origin setup.
import type {
  Instrument,
  Me,
  OpenOrder,
  OrderResult,
  SessionInfo,
  Side,
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
}
