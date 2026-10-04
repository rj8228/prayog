import { useCallback, useEffect, useRef } from 'react'
import { api } from '../api/rest'
import type { Fill } from '../api/types'
import { withToken } from '../auth/auth'
import { useAppDispatch } from '../hooks'
import { startPrivateFeed } from '../market/feed'
import { fill, me, openOrders, signedOut } from './accountSlice'

/**
 * Keeps the account slice live while signed in: the private feed (fills, order updates) plus the exchange's list of
 * open orders, re-read on every update and every 5 s. The exchange's list is the truth (see the bot guide).
 * Returns `refresh`, to re-read open orders at once (after placing or cancelling).
 */
export function useAccountFeed(token: string | null): () => void {
  const dispatch = useAppDispatch()
  const reloadNow = useRef<() => void>(() => undefined)
  useEffect(() => {
    if (!token) {
      dispatch(signedOut())
      return
    }
    let active = true
    let pending: ReturnType<typeof setTimeout> | undefined
    const reload = () =>
      withToken(api.openOrders)
        .then((o) => active && dispatch(openOrders(o)))
        .catch(() => undefined)
    // Many updates arrive together (a sweep fills several orders): refetch once after they settle.
    const soon = () => {
      clearTimeout(pending)
      pending = setTimeout(() => void reload(), 150)
    }
    reloadNow.current = soon
    void withToken(api.me).then((m) => active && dispatch(me(m)))
    void reload()
    const timer = setInterval(() => void reload(), 5000)
    const stop = startPrivateFeed(token, (m) => {
      const message = m as { type: string }
      if (message.type === 'fill') dispatch(fill(m as Fill))
      if (message.type === 'fill' || message.type === 'order') soon()
    })
    return () => {
      active = false
      clearInterval(timer)
      clearTimeout(pending)
      stop()
      reloadNow.current = () => undefined
    }
  }, [token, dispatch])
  return useCallback(() => reloadNow.current(), [])
}
