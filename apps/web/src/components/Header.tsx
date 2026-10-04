import { signIn, signOut } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { useAppSelector } from '../hooks'
import { simClock } from '../market/format'

export function Header() {
  const { session, simTime, connected } = useAppSelector((s) => s.market)
  const { user } = useAuth()
  return (
    <header className="header">
      <div className="brand">
        Prayog <span className="muted">simulated exchange</span>
      </div>
      <div className="status">
        <span className={`badge session-${session.toLowerCase()}`}>
          {session}
        </span>
        <span className="clock" title="Sim time, IST">
          {simTime ? simClock(simTime) : '--:--:--'} IST
        </span>
        <span
          className={`dot ${connected ? 'on' : 'off'}`}
          title={connected ? 'live' : 'reconnecting'}
        />
      </div>
      <div className="user">
        {user ? (
          <>
            <span className="muted">{user.profile.preferred_username}</span>
            <button onClick={() => void signOut()}>Sign out</button>
          </>
        ) : (
          <button className="primary" onClick={() => void signIn()}>
            Sign in to trade
          </button>
        )}
      </div>
    </header>
  )
}
