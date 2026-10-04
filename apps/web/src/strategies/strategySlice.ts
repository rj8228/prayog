// What the UI shows about each strategy. The running machinery lives in runner.ts; this is its report.
import { createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { RiskLimits } from './risk'
import type { ParamValue } from './types'

export type Status =
  'starting' | 'running' | 'stopping' | 'stopped' | 'done' | 'error'

export interface LogLine {
  t: number // wall-clock ms
  text: string
  kind: 'action' | 'fill' | 'risk' | 'info' | 'error'
}

export interface StrategyView {
  id: string
  kind: string
  name: string
  symbol: string
  account: string
  params: Record<string, ParamValue>
  limits: RiskLimits
  status: Status
  reason: string | null
  startedAt: number
  position: number
  averagePrice: number
  realized: number
  unrealized: number
  orders: number
  rejected: number
  fills: number
  pnlHistory: number[] // P&L sampled every tick, for the sparkline
  log: LogLine[]
}

const slice = createSlice({
  name: 'strategies',
  initialState: { list: [] as StrategyView[] },
  reducers: {
    added(state, action: PayloadAction<StrategyView>) {
      state.list.unshift(action.payload)
    },
    updated(
      state,
      action: PayloadAction<{
        id: string
        patch: Partial<StrategyView>
        log?: LogLine[]
      }>,
    ) {
      const s = state.list.find((x) => x.id === action.payload.id)
      if (!s) return
      Object.assign(s, action.payload.patch)
      if (action.payload.log?.length)
        s.log = [...action.payload.log.reverse(), ...s.log].slice(0, 200)
    },
    removed(state, action: PayloadAction<string>) {
      state.list = state.list.filter((x) => x.id !== action.payload)
    },
  },
})

export const { added, updated, removed } = slice.actions
export default slice.reducer
