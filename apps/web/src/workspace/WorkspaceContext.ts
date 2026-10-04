import { createContext, useContext } from 'react'
import type { Side } from '../api/types'

export interface TradeSettings {
  oneClick: boolean // a click in the order book places an order at once
  confirm: boolean // ask before sending one-click and keyboard orders
  quantity: number // default quantity for one-click orders
}

export interface WorkspaceState {
  symbol: string
  setSymbol: (s: string) => void
  price: number | null // last price clicked in the ladder or depth chart
  setPrice: (p: number | null) => void
  side: Side
  setSide: (s: Side) => void
  explain: boolean
  settings: TradeSettings
  setSettings: (s: TradeSettings) => void
  placed: () => void // something placed or cancelled an order: refresh views
}

export const WorkspaceContext = createContext<WorkspaceState | null>(null)

export function useWorkspace(): WorkspaceState {
  const ws = useContext(WorkspaceContext)
  if (!ws) throw new Error('useWorkspace outside the workspace')
  return ws
}
