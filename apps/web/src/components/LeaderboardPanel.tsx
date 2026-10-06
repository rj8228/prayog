import { useEffect, useState } from 'react'
import { postTradeApi } from '../api/rest'
import type { Leaderboard } from '../api/types'
import { useAppSelector } from '../hooks'
import { rupees } from '../market/format'

/** Top accounts by net P&L (realised + unrealised - charges), from the post-trade leaderboard (S17). Public. */
export function LeaderboardPanel({ limit = 15 }: { limit?: number }) {
  const me = useAppSelector((s) => s.account.me)
  const [board, setBoard] = useState<Leaderboard | null>(null)
  const [error, setError] = useState<string | null>(null)
  useEffect(() => {
    let active = true
    const load = () =>
      postTradeApi
        .leaderboard(limit)
        .then((b) => {
          if (active) {
            setBoard(b)
            setError(null)
          }
        })
        .catch((e: Error) => active && setError(e.message))
    void load()
    const timer = setInterval(() => void load(), 3000)
    return () => {
      active = false
      clearInterval(timer)
    }
  }, [limit])
  return (
    <section className="panel">
      <div className="panel-title">
        Leaderboard{' '}
        {board && <span className="muted">· {board.accounts} accounts</span>}
      </div>
      {!board ? (
        <p className="muted">{error ?? 'Loading...'}</p>
      ) : board.entries.length === 0 ? (
        <p className="muted">Nobody has traded yet.</p>
      ) : (
        <table className="compact">
          <tbody>
            {board.entries.map((e) => (
              <tr
                key={e.accountId}
                className={me?.accountId === e.accountId ? 'mine' : ''}
              >
                <td className="muted">#{e.rank}</td>
                <td title={String(e.accountId)}>
                  {e.name ?? `account ${String(e.accountId).slice(0, 6)}…`}
                  {me?.accountId === e.accountId ? ' (you)' : ''}
                </td>
                <td className={e.netPnl >= 0 ? 'px up' : 'px down'}>
                  ₹{rupees(e.netPnl)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
