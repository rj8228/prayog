// Your account as this tab knows it: open orders (from the exchange), fills (from the private feed), positions
// derived from those fills, and short-lived notifications.
import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { Fill, Me, OpenOrder } from '../api/types'
import { rupees } from '../market/format'
import { applyFill, FLAT, type Position } from './positions'

export interface Toast {
  id: number
  text: string
  kind: 'buy' | 'sell' | 'info' | 'error'
}

export interface AccountState {
  me: Me | null
  openOrders: OpenOrder[]
  fills: Fill[] // newest first, capped
  positions: Record<string, Position>
  toasts: Toast[]
  seenTrades: Record<string, true> // `${tradeId}-${orderId}`: a reconnect must not count a fill twice
}

const initialState: AccountState = {
  me: null,
  openOrders: [],
  fills: [],
  positions: {},
  toasts: [],
  seenTrades: {},
}
let nextToast = 1

const accountSlice = createSlice({
  name: 'account',
  initialState,
  reducers: {
    me(state, action: PayloadAction<Me | null>) {
      state.me = action.payload
    },
    openOrders(state, action: PayloadAction<OpenOrder[]>) {
      state.openOrders = action.payload
    },
    fill(state, action: PayloadAction<Fill>) {
      const f = action.payload
      const key = `${f.tradeId}-${f.orderId}`
      if (state.seenTrades[key]) return
      state.seenTrades[key] = true
      state.fills.unshift(f)
      if (state.fills.length > 200) state.fills.pop()
      state.positions[f.symbol] = applyFill(
        state.positions[f.symbol] ?? FLAT,
        f.side,
        f.quantity,
        f.price,
      )
      state.toasts.push({
        id: nextToast++,
        text: `${f.side === 'BUY' ? 'Bought' : 'Sold'} ${f.quantity} ${f.symbol} @ ${rupees(f.price)}`,
        kind: f.side === 'BUY' ? 'buy' : 'sell',
      })
    },
    notify(state, action: PayloadAction<Omit<Toast, 'id'>>) {
      state.toasts.push({ ...action.payload, id: nextToast++ })
    },
    dismiss(state, action: PayloadAction<number>) {
      state.toasts = state.toasts.filter((t) => t.id !== action.payload)
    },
    signedOut() {
      return initialState
    },
  },
})

export const { me, openOrders, fill, notify, dismiss, signedOut } =
  accountSlice.actions
export default accountSlice.reducer
