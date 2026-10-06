import { Provider } from 'react-redux'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { AdminPage } from './admin/AdminPage'
import { CallbackPage } from './CallbackPage'
import { MarketPage } from './MarketPage'
import { OpsPage } from './ops/OpsPage'
import { store } from './store'

const router = createBrowserRouter([
  { path: '/', element: <MarketPage /> },
  { path: '/callback', element: <CallbackPage /> },
  { path: '/admin', element: <AdminPage /> },
  { path: '/ops', element: <OpsPage /> },
])

function App() {
  return (
    <Provider store={store}>
      <AuthProvider>
        <RouterProvider router={router} />
      </AuthProvider>
    </Provider>
  )
}

export default App
