import { Link, useLocation } from 'react-router'
import { signIn, signOut } from '../auth/auth'
import { rolesOf } from '../auth/roles'
import { useAuth } from '../auth/useAuth'
import { useAppSelector } from '../hooks'
import { simClock } from '../market/format'

export function Header() {
  const { session, simTime, connected } = useAppSelector((s) => s.market)
  const { user, token } = useAuth()
  const isAdmin = rolesOf(token).includes('admin')
  const { pathname } = useLocation()
  return (
    <header className="header">
      <div className="brand">
        <Link to="/">Prayog</Link>{' '}
        <span className="muted">simulated exchange</span>
        <nav className="tabs">
          <Link className={pathname === '/' ? 'active' : ''} to="/">
            Market
          </Link>
          {isAdmin && (
            <Link className={pathname === '/admin' ? 'active' : ''} to="/admin">
              Admin
            </Link>
          )}
          <a
            href="https://rj8228.github.io/prayog/"
            target="_blank"
            rel="noreferrer"
          >
            Docs
          </a>
        </nav>
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
