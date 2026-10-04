import { configureStore } from '@reduxjs/toolkit'
import market from './market/marketSlice'

export const store = configureStore({ reducer: { market } })

export type RootState = ReturnType<typeof store.getState>
export type AppDispatch = typeof store.dispatch
