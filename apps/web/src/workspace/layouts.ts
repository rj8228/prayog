// Panel catalog and layout presets. Layouts use a 12-column grid with 30 px rows on wide screens; narrower screens
// get layouts derived automatically (a single column on phones).
import type { Layout, ResponsiveLayouts } from 'react-grid-layout'

export type PanelId =
  | 'chart'
  | 'ladder'
  | 'tape'
  | 'ticket'
  | 'orders'
  | 'fills'
  | 'positions'
  | 'watchlist'
  | 'depth'
  | 'strategies'

export const PANEL_TITLES: Record<PanelId, string> = {
  chart: 'Price chart',
  ladder: 'Order book',
  tape: 'Trades',
  ticket: 'Order ticket',
  orders: 'My orders',
  fills: 'My fills',
  positions: 'Positions & P&L',
  watchlist: 'Watchlist',
  depth: 'Depth chart',
  strategies: 'Strategies',
}

export const MIN_SIZE: Record<PanelId, { minW: number; minH: number }> = {
  chart: { minW: 3, minH: 8 },
  ladder: { minW: 2, minH: 8 },
  tape: { minW: 2, minH: 6 },
  ticket: { minW: 2, minH: 9 },
  orders: { minW: 3, minH: 5 },
  fills: { minW: 2, minH: 4 },
  positions: { minW: 3, minH: 4 },
  watchlist: { minW: 2, minH: 5 },
  depth: { minW: 3, minH: 6 },
  strategies: { minW: 4, minH: 8 },
}

type Item = [PanelId, number, number, number, number] // id, x, y, w, h

function layout(items: Item[]): Layout {
  return items.map(([i, x, y, w, h]) => ({ i, x, y, w, h, ...MIN_SIZE[i] }))
}

export interface Preset {
  name: string
  description: string
  layouts: ResponsiveLayouts
}

export const PRESETS: Record<string, Preset> = {
  trader: {
    name: 'Trader',
    description: 'Chart, book and tape on top; ticket, orders and P&L below',
    layouts: {
      lg: layout([
        ['chart', 0, 0, 7, 14],
        ['ladder', 7, 0, 3, 14],
        ['tape', 10, 0, 2, 14],
        ['ticket', 0, 14, 3, 12],
        ['orders', 3, 14, 5, 7],
        ['fills', 3, 21, 5, 5],
        ['positions', 8, 14, 4, 12],
      ]),
    },
  },
  scalper: {
    name: 'Scalper',
    description:
      'A tall order book for one-click trading, with the depth chart and tape beside it',
    layouts: {
      lg: layout([
        ['ladder', 0, 0, 3, 22],
        ['ticket', 3, 0, 3, 12],
        ['depth', 3, 12, 3, 10],
        ['tape', 6, 0, 2, 22],
        ['chart', 8, 0, 4, 11],
        ['orders', 8, 11, 4, 6],
        ['positions', 8, 17, 4, 5],
      ]),
    },
  },
  watcher: {
    name: 'Watcher',
    description: 'Everything to watch the market, nothing to trade with',
    layouts: {
      lg: layout([
        ['chart', 0, 0, 8, 14],
        ['watchlist', 8, 0, 4, 14],
        ['depth', 0, 14, 5, 10],
        ['ladder', 5, 14, 4, 10],
        ['tape', 9, 14, 3, 10],
      ]),
    },
  },
  quant: {
    name: 'Quant',
    description:
      'Strategies front and centre, with the chart, positions and fills to watch them',
    layouts: {
      lg: layout([
        ['strategies', 0, 0, 7, 16],
        ['chart', 7, 0, 5, 9],
        ['ladder', 7, 9, 5, 7],
        ['positions', 0, 16, 6, 8],
        ['fills', 6, 16, 6, 8],
      ]),
    },
  },
}

export const BREAKPOINTS = { lg: 1100, md: 800, sm: 560, xs: 0 }
export const COLS = { lg: 12, md: 8, sm: 6, xs: 1 }

/** Places a panel that was added from the menu: at the bottom, full width of half the grid. */
export function addPanel(
  layouts: ResponsiveLayouts,
  id: PanelId,
): ResponsiveLayouts {
  const lg = layouts.lg ?? []
  const bottom = lg.reduce((max, item) => Math.max(max, item.y + item.h), 0)
  return {
    lg: [
      ...lg,
      {
        i: id,
        x: 0,
        y: bottom,
        w: 6,
        h: Math.max(8, MIN_SIZE[id].minH),
        ...MIN_SIZE[id],
      },
    ],
  }
}

export function panelsOf(layouts: ResponsiveLayouts): PanelId[] {
  return (layouts.lg ?? []).map((item) => item.i as PanelId)
}
