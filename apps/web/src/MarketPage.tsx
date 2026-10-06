import { useEffect, useState } from 'react'
import { shallowEqual } from 'react-redux'
import { useAccountFeed } from './account/useAccountFeed'
import { api } from './api/rest'
import { useAuth } from './auth/useAuth'
import { Header } from './components/Header'
import { TickerBar } from './components/TickerBar'
import { Toasts } from './components/Toasts'
import { useAppDispatch, useAppSelector } from './hooks'
import { history } from './market/marketSlice'
import { Workspace } from './workspace/Workspace'

export function MarketPage() {
  const dispatch = useAppDispatch()
  const { token } = useAuth()
  const symbols = useAppSelector(
    (s) => Object.keys(s.market.books).sort(),
    shallowEqual,
  )
  const [selected, setSelected] = useState('INFY')
  const known = symbols.includes(selected)

  const refreshOrders = useAccountFeed(token)

  // Load more trade history for the chart when a symbol is first shown.
  useEffect(() => {
    if (!known) return
    void api
      .trades(selected)
      .then((t) => dispatch(history({ symbol: selected, trades: t })))
  }, [selected, known, dispatch])

  return (
    <div className="app">
      <Header />
      <TickerBar selected={selected} onSelect={setSelected} />
      <Workspace
        symbol={selected}
        setSymbol={setSelected}
        onPlaced={refreshOrders}
      />
      <Toasts />
      <footer className="footer muted">
        Prices in ₹ (integer paise inside). Sim time in IST. Simulated market:
        no real money.
      </footer>
    </div>
  )
}
