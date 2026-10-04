import { useEffect } from 'react'
import { dismiss } from '../account/accountSlice'
import { useAppDispatch, useAppSelector } from '../hooks'

/** Fill and order notifications, bottom right; each fades after 5 s or on click. */
export function Toasts() {
  const toasts = useAppSelector((s) => s.account.toasts)
  const dispatch = useAppDispatch()
  useEffect(() => {
    if (!toasts.length) return
    const oldest = toasts[0]
    const timer = setTimeout(() => dispatch(dismiss(oldest.id)), 5000)
    return () => clearTimeout(timer)
  }, [toasts, dispatch])
  return (
    <div className="toasts" role="status" aria-live="polite">
      {toasts.slice(-5).map((t) => (
        <button
          key={t.id}
          className={`toast ${t.kind}`}
          onClick={() => dispatch(dismiss(t.id))}
        >
          {t.text}
        </button>
      ))}
    </div>
  )
}
