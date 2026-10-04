import { configureStore } from '@reduxjs/toolkit'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { Provider } from 'react-redux'
import { afterEach, describe, expect, it, vi } from 'vitest'
import account from '../account/accountSlice'
import market, { message } from '../market/marketSlice'
import {
  WorkspaceContext,
  type WorkspaceState,
} from '../workspace/WorkspaceContext'
import { Ladder } from './Ladder'

const auth = vi.hoisted(() => ({ token: null as string | null }))
const place = vi.hoisted(() => vi.fn())
vi.mock('../auth/useAuth', () => ({ useAuth: () => ({ token: auth.token }) }))
vi.mock('../auth/auth', () => ({
  withToken: (call: (t: string) => unknown) => call('token'),
}))
vi.mock('../api/rest', () => ({ api: { place } }))

function setup(overrides: Partial<WorkspaceState> = {}) {
  const store = configureStore({ reducer: { market, account } })
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
  const ws: WorkspaceState = {
    symbol: 'TCS',
    setSymbol: vi.fn(),
    price: null,
    setPrice: vi.fn(),
    side: 'BUY',
    setSide: vi.fn(),
    explain: false,
    settings: { oneClick: false, confirm: true, quantity: 7 },
    setSettings: vi.fn(),
    placed: vi.fn(),
    ...overrides,
  }
  render(
    <Provider store={store}>
      <WorkspaceContext.Provider value={ws}>
        <Ladder />
      </WorkspaceContext.Provider>
    </Provider>,
  )
  return { store, ws }
}

describe('Ladder', () => {
  afterEach(cleanup)

  it('shows asks above bids with the spread, and a click picks the price', () => {
    auth.token = null
    const { ws } = setup()
    const rows = screen.getAllByRole('row').map((r) => r.textContent)
    expect(rows.findIndex((r) => r?.includes('4,000.00'))).toBeLessThan(
      rows.findIndex((r) => r?.includes('3,999.50')),
    )
    expect(screen.getByText('spread 0.50')).toBeInTheDocument()
    fireEvent.click(screen.getByText('3,999.50'))
    expect(ws.setPrice).toHaveBeenCalledWith(399950)
    expect(place).not.toHaveBeenCalled()
  })

  it('with 1-click on, clicking an ask buys there once confirmed', async () => {
    auth.token = 'token'
    place.mockReset().mockResolvedValue({ status: 'resting', fills: [] })
    const confirm = vi
      .spyOn(window, 'confirm')
      .mockReturnValueOnce(false)
      .mockReturnValueOnce(true)
    const { ws, store } = setup({
      settings: { oneClick: true, confirm: true, quantity: 7 },
    })
    fireEvent.click(screen.getByText('4,000.00'))
    expect(place).not.toHaveBeenCalled() // declined
    fireEvent.click(screen.getByText('4,000.00'))
    await vi.waitFor(() => expect(ws.placed).toHaveBeenCalled())
    expect(place).toHaveBeenCalledTimes(1)
    expect(place).toHaveBeenCalledWith('token', {
      symbol: 'TCS',
      side: 'BUY',
      type: 'LIMIT',
      price: 400000,
      quantity: 7,
    })
    expect(store.getState().account.toasts[0].text).toContain(
      'Buy 7 TCS @ 4,000.00: resting',
    )
    confirm.mockRestore()
  })
})
