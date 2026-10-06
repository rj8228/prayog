import { Link } from 'react-router'
import { adminApi } from '../api/rest'
import { useAuth } from '../auth/useAuth'
import { rolesOf } from '../auth/roles'
import { signIn } from '../auth/auth'
import { Header } from '../components/Header'
import { useAppSelector } from '../hooks'
import { AccountsPanel } from './AccountsPanel'
import { HealthPanel, MarketControl, SimulationPanel } from './ControlPanels'
import { ReplayViewer } from './ReplayViewer'
import { SelfTestPanel } from './SelfTestPanel'
import { usePoll } from './useAdmin'

export function AdminPage() {
  const { token } = useAuth()
  const isAdmin = rolesOf(token).includes('admin')
  if (!token) {
    return (
      <div className="app">
        <Header />
        <p className="pad">
          The admin console needs an admin sign-in.{' '}
          <button onClick={() => void signIn()}>Sign in</button>
        </p>
      </div>
    )
  }
  if (!isAdmin) {
    return (
      <div className="app">
        <Header />
        <p className="pad">
          Your account has no admin role. <Link to="/">Back to the market</Link>
        </p>
      </div>
    )
  }
  return <AdminConsole />
}

function AdminConsole() {
  // Module functions are stable references, so the polls don't restart on every render.
  const overview = usePoll(adminApi.overview, 3000)
  const accounts = usePoll(adminApi.accounts, 5000)
  const symbols = useAppSelector((s) => Object.keys(s.market.books).sort())
  const knownSymbols = symbols.length
    ? symbols
    : ['HDFCBANK', 'INFY', 'RELIANCE', 'TCS']
  return (
    <div className="app">
      <Header />
      <main className="admin-grid">
        <MarketControl
          status={overview.value?.status ?? null}
          onChange={overview.refresh}
        />
        <SimulationPanel
          simulation={overview.value?.simulation ?? null}
          symbols={knownSymbols}
          onChange={overview.refresh}
        />
        <SelfTestPanel />
        <HealthPanel overview={overview.value} error={overview.error} />
        <AccountsPanel accounts={accounts.value} onChange={accounts.refresh} />
        <ReplayViewer symbols={knownSymbols} />
      </main>
    </div>
  )
}
