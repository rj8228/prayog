import { Provider } from 'react-redux'
import { createBrowserRouter, RouterProvider } from 'react-router'
import { AuthProvider } from './auth/AuthProvider'
import { CallbackPage } from './CallbackPage'
import { MarketPage } from './MarketPage'
import { store } from './store'

const router = createBrowserRouter([
  { path: '/', element: <MarketPage /> },
  { path: '/callback', element: <CallbackPage /> },
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
