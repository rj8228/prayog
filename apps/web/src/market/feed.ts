// The public market-data WebSocket, dispatched into the store. Reconnects after a drop or a sequence gap.
import type { MarketMessage } from '../api/types'
import type { AppDispatch, RootState } from '../store'
import { connected, message } from './marketSlice'

function wsUrl(path: string): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}${path}`
}

export function startMarketFeed(
  dispatch: AppDispatch,
  getState: () => RootState,
): () => void {
  let socket: WebSocket | null = null
  let stopped = false
  let retry: ReturnType<typeof setTimeout> | undefined

  const open = () => {
    socket = new WebSocket(wsUrl('/api/v1/ws/market?depth=15'))
    socket.onopen = () => dispatch(connected(true))
    socket.onmessage = (event) => {
      dispatch(message(JSON.parse(event.data as string) as MarketMessage))
      const stale = Object.values(getState().market.books).some((b) => b.stale)
      if (stale) socket?.close() // resubscribe: fresh snapshots fix the gap
    }
    socket.onclose = () => {
      dispatch(connected(false))
      if (!stopped) retry = setTimeout(open, 1000)
    }
  }
  open()
  return () => {
    stopped = true
    clearTimeout(retry)
    socket?.close()
  }
}

/** The signed-in account's private feed (orders and fills). Browsers pass the token as a query parameter. */
export function startPrivateFeed(
  token: string,
  onMessage: (m: unknown) => void,
): () => void {
  let socket: WebSocket | null = null
  let stopped = false
  let retry: ReturnType<typeof setTimeout> | undefined
  const open = () => {
    socket = new WebSocket(
      wsUrl(`/api/v1/ws/private?access_token=${encodeURIComponent(token)}`),
    )
    socket.onmessage = (event) => onMessage(JSON.parse(event.data as string))
    socket.onclose = () => {
      if (!stopped) retry = setTimeout(open, 2000)
    }
  }
  open()
  return () => {
    stopped = true
    clearTimeout(retry)
    socket?.close()
  }
}
