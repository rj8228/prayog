import { useEffect } from 'react'
import { Provider } from 'react-redux'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { AdminPage } from './admin/AdminPage'
import { CallbackPage } from './CallbackPage'
import { MarketPage } from './MarketPage'
import { OpsPage } from './ops/OpsPage'
import { startMarketFeed } from './market/feed'
import { store } from './store'

const router = createBrowserRouter([
  { path: '/', element: <MarketPage /> },
  { path: '/callback', element: <CallbackPage /> },
  { path: '/admin', element: <AdminPage /> },
  { path: '/ops', element: <OpsPage /> },
])

/**
 * The public market feed, connected once for the whole app: every page (market, ops, admin) and the header see the
 * same live session, clock and books, and moving between pages does not reconnect.
 */
function MarketFeed() {
  useEffect(() => startMarketFeed(store.dispatch, store.getState), [])
  return null
}

function App() {
  return (
    <Provider store={store}>
      <AuthProvider>
        <MarketFeed />
        <RouterProvider router={router} />
      </AuthProvider>
    </Provider>
  )
}

export default App
