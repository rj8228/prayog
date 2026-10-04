import { configureStore } from '@reduxjs/toolkit'
import { fireEvent, render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { describe, expect, it, vi } from 'vitest'
import market, { message } from '../market/marketSlice'
import { Ladder } from './Ladder'

describe('Ladder', () => {
  it('shows asks above bids with the spread, and a click picks the price', () => {
    const store = configureStore({ reducer: { market } })
    store.dispatch(
      message({
        type: 'snapshot',
        symbol: 'TCS',
        seq: 1,
        session: 'OPEN',
        bids: [{ price: 399950, quantity: 40, orders: 1 }],
        asks: [{ price: 400000, quantity: 25, orders: 2 }],
        trades: [],
        ticker: {
          symbol: 'TCS',
          referencePrice: 400000,
          last: 0,
          open: 0,
          high: 0,
          low: 0,
          volume: 0,
          trades: 0,
          bestBid: 399950,
          bestAsk: 400000,
        },
      }),
    )
    const onPrice = vi.fn()
    render(
      <Provider store={store}>
        <Ladder symbol="TCS" onPrice={onPrice} />
      </Provider>,
    )
    const rows = screen.getAllByRole('row').map((r) => r.textContent)
    expect(rows.findIndex((r) => r?.includes('4,000.00'))).toBeLessThan(
      rows.findIndex((r) => r?.includes('3,999.50')),
    )
    expect(screen.getByText('spread 0.50')).toBeInTheDocument()
    fireEvent.click(screen.getByText('3,999.50'))
    expect(onPrice).toHaveBeenCalledWith(399950)
  })
})
