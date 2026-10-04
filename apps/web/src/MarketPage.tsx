import { useEffect, useState } from 'react'
import { api } from './api/rest'
import { Header } from './components/Header'
import { Ladder } from './components/Ladder'
import { MyOrders } from './components/MyOrders'
import { OrderTicket } from './components/OrderTicket'
import { PriceChart } from './components/PriceChart'
import { TickerBar } from './components/TickerBar'
import { TradeTape } from './components/TradeTape'
import { useAppDispatch, useAppSelector } from './hooks'
import { startMarketFeed } from './market/feed'
import { history } from './market/marketSlice'
import { store } from './store'

export function MarketPage() {
  const dispatch = useAppDispatch()
  const symbols = useAppSelector((s) => Object.keys(s.market.books).sort())
  const [selected, setSelected] = useState('INFY')
  const [candleSeconds, setCandleSeconds] = useState(10)
  const [clickedPrice, setClickedPrice] = useState<number | null>(null)
  const [refresh, setRefresh] = useState(0)
  const trades = useAppSelector((s) => s.market.books[selected]?.trades ?? [])
  const known = symbols.includes(selected)

  useEffect(() => startMarketFeed(dispatch, store.getState), [dispatch])

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
      <main className="grid">
        <PriceChart
          trades={trades}
          interval={candleSeconds}
          onInterval={setCandleSeconds}
        />
        <Ladder symbol={selected} onPrice={setClickedPrice} />
        <TradeTape symbol={selected} />
        <OrderTicket
          symbol={selected}
          price={clickedPrice}
          onPlaced={() => setRefresh((r) => r + 1)}
        />
        <MyOrders refresh={refresh} />
      </main>
      <footer className="footer muted">
        Prices in ₹ (integer paise inside). Sim time in IST. Simulated market:
        no real money.
      </footer>
    </div>
  )
}
