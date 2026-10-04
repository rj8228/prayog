import { configureStore } from '@reduxjs/toolkit'
import account from './account/accountSlice'
import market from './market/marketSlice'

export const store = configureStore({ reducer: { market, account } })

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
