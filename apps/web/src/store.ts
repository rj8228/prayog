import { configureStore } from '@reduxjs/toolkit'
import account from './account/accountSlice'
import market from './market/marketSlice'
import strategies from './strategies/strategySlice'

export const store = configureStore({
  reducer: { market, account, strategies },
})

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
