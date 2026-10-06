import { useCallback, useEffect, useState } from 'react'
import { withToken } from '../auth/auth'

/**
 * Polls an authenticated call every `ms` milliseconds; returns the latest value, an error message and a refresh
 * function. With `enabled` false (signed out) it does nothing, so no silent sign-in is attempted.
 */
export function usePoll<T>(
  call: (token: string) => Promise<T>,
  ms: number,
  enabled = true,
) {
  const [value, setValue] = useState<T | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [tick, setTick] = useState(0)
  const refresh = useCallback(() => setTick((t) => t + 1), [])
  useEffect(() => {
    if (!enabled) return
    let active = true
    const load = () =>
      withToken(call)
        .then((v) => {
          if (active) {
            setValue(v)
            setError(null)
          }
        })
        .catch((e: Error) => active && setError(e.message))
    void load()
    const timer = setInterval(() => void load(), ms)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [call, ms, tick, enabled])
  return { value, error, refresh }
}

/** Runs an admin action and reports how it went in a short status line. */
export function useAction() {
  const [status, setStatus] = useState<{ ok: boolean; text: string } | null>(
    null,
  )
  const run = useCallback(
    async (label: string, action: (token: string) => Promise<unknown>) => {
      try {
        await withToken(action)
        setStatus({ ok: true, text: `${label}: done` })
      } catch (e) {
        setStatus({ ok: false, text: `${label}: ${(e as Error).message}` })
      }
    },
    [],
  )
  return { status, run }
}
