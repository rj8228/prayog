import { useEffect, useState, type ReactNode } from 'react'
import { shallowEqual } from 'react-redux'
import {
  ResponsiveGridLayout,
  useContainerWidth,
  type Layout,
  type ResponsiveLayouts,
} from 'react-grid-layout'
import 'react-grid-layout/css/styles.css'
import { notify } from '../account/accountSlice'
import { api } from '../api/rest'
import type { Side } from '../api/types'
import { withToken } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { DepthChart } from '../components/DepthChart'
import { Ladder } from '../components/Ladder'
import { MyFills } from '../components/MyFills'
import { MyOrders } from '../components/MyOrders'
import { OrderTicket } from '../components/OrderTicket'
import { Positions } from '../components/Positions'
import { PriceChart } from '../components/PriceChart'
import { TradeTape } from '../components/TradeTape'
import { Watchlist } from '../components/Watchlist'
import { useAppDispatch, useAppSelector } from '../hooks'
import { StrategiesPanel } from '../strategies/StrategiesPanel'
import { EXPLAIN } from './explain'
import {
  addPanel,
  BREAKPOINTS,
  COLS,
  PANEL_TITLES,
  panelsOf,
  PRESETS,
  type PanelId,
} from './layouts'
import { SHORTCUTS, shortcutFor, stepSymbol } from './shortcuts'
import { load, save } from './storage'
import { removePanel, swapPanels } from './swap'
import {
  useWorkspace,
  WorkspaceContext,
  type TradeSettings,
  type WorkspaceState,
} from './WorkspaceContext'

interface Saved {
  preset: string
  layouts: ResponsiveLayouts
  locked: boolean
  explain: boolean
  settings: TradeSettings
}

const DEFAULTS: Saved = {
  preset: 'trader',
  layouts: PRESETS.trader.layouts,
  locked: false,
  explain: false,
  settings: { oneClick: false, confirm: true, quantity: 10 },
}

function Chart() {
  const { symbol } = useWorkspace()
  const trades = useAppSelector((s) => s.market.books[symbol]?.trades ?? [])
  const [interval, setInterval] = useState(10)
  return (
    <PriceChart trades={trades} interval={interval} onInterval={setInterval} />
  )
}

function Tape() {
  return <TradeTape symbol={useWorkspace().symbol} />
}

const PANELS: Record<PanelId, () => ReactNode> = {
  chart: () => <Chart />,
  ladder: () => <Ladder />,
  tape: () => <Tape />,
  ticket: () => <OrderTicket />,
  orders: () => <MyOrders />,
  fills: () => <MyFills />,
  positions: () => <Positions />,
  watchlist: () => <Watchlist />,
  depth: () => <DepthChart />,
  strategies: () => <StrategiesPanel />,
}

/**
 * The trading workspace: panels you can drag (by their title), resize (bottom-right corner), swap, add and remove.
 * The layout, presets and settings are saved per user in this browser.
 */
export function Workspace({
  symbol,
  setSymbol,
  onPlaced,
  children,
}: {
  symbol: string
  setSymbol: (s: string) => void
  onPlaced: () => void
  children?: ReactNode
}) {
  const { token } = useAuth()
  const dispatch = useAppDispatch()
  const me = useAppSelector((s) => s.account.me)
  const symbols = useAppSelector(
    (s) => Object.keys(s.market.books).sort(),
    shallowEqual,
  )
  const storageKey = `prayog.workspace.${me?.subject ?? 'guest'}`

  const [saved, setSaved] = useState<Saved>(() => ({
    ...DEFAULTS,
    ...load(storageKey, DEFAULTS),
  }))
  // Signing in switches to that user's saved workspace (state adjusted during render when the key changes).
  const [loadedKey, setLoadedKey] = useState(storageKey)
  if (loadedKey !== storageKey) {
    setLoadedKey(storageKey)
    setSaved({ ...DEFAULTS, ...load(storageKey, DEFAULTS) })
  }
  const update = (change: Partial<Saved>) =>
    setSaved((s) => {
      const next = { ...s, ...change }
      save(storageKey, next)
      return next
    })

  const [price, setPrice] = useState<number | null>(null)
  const [side, setSide] = useState<Side>('BUY')
  const [help, setHelp] = useState(false)
  const { width, containerRef, mounted } = useContainerWidth()

  const context: WorkspaceState = {
    symbol,
    setSymbol,
    price,
    setPrice,
    side,
    setSide,
    explain: saved.explain,
    settings: saved.settings,
    setSettings: (settings) => update({ settings }),
    placed: onPlaced,
  }

  // Keyboard shortcuts. Re-bound on every render so the handler always sees the current state.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const action = shortcutFor(e)
      if (!action) return
      e.preventDefault()
      if (action === 'buy' || action === 'sell') {
        setSide(action === 'buy' ? 'BUY' : 'SELL')
        document.getElementById('ticket-quantity')?.focus()
      } else if (action === 'previous-symbol' || action === 'next-symbol') {
        setSymbol(
          stepSymbol(symbols, symbol, action === 'next-symbol' ? 1 : -1),
        )
      } else if (action === 'cancel-all') {
        if (!token || !window.confirm('Cancel all your open orders?')) return
        void withToken(api.cancelAll).then((r) => {
          dispatch(
            notify({
              text: `Cancelled ${r.cancelled} of ${r.requested} orders`,
              kind: 'info',
            }),
          )
          onPlaced()
        })
      } else if (action === 'toggle-explain') {
        update({ explain: !saved.explain })
      } else if (action === 'toggle-lock') {
        update({ locked: !saved.locked })
      } else {
        setHelp((h) => !h)
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  })

  const panels = panelsOf(saved.layouts)
  const missing = (Object.keys(PANEL_TITLES) as PanelId[]).filter(
    (p) => !panels.includes(p),
  )
  const lg: Layout = saved.layouts.lg ?? []
  // Layout edits that are not drags (add, remove, swap) start from the wide layout; narrower ones are re-derived.
  const setLg = (next: Layout) =>
    update({ layouts: { lg: next }, preset: 'custom' })

  const grid = panels.map((id) => (
    <div key={id} className="ws-item">
      {PANELS[id]()}
      {!saved.locked && (
        <div className="ws-chrome">
          <select
            aria-label={`Swap ${PANEL_TITLES[id]} with`}
            title="Swap places with…"
            value=""
            onChange={(e) => setLg(swapPanels(lg, id, e.target.value))}
          >
            <option value="">⇄</option>
            {panels
              .filter((p) => p !== id)
              .map((p) => (
                <option key={p} value={p}>
                  swap with {PANEL_TITLES[p]}
                </option>
              ))}
          </select>
          <button
            title={`Remove ${PANEL_TITLES[id]}`}
            onClick={() => setLg(removePanel(lg, id))}
          >
            ×
          </button>
        </div>
      )}
      {saved.explain && (
        <div className="explain">
          <b>{PANEL_TITLES[id]}:</b> {EXPLAIN[id].text}{' '}
          <a href={EXPLAIN[id].link} target="_blank" rel="noreferrer">
            {EXPLAIN[id].label} ↗
          </a>
        </div>
      )}
    </div>
  ))

  return (
    <WorkspaceContext.Provider value={context}>
      <div className="ws-toolbar">
        <label>
          Layout
          <select
            value={saved.preset}
            onChange={(e) => {
              const p = PRESETS[e.target.value]
              if (p) update({ preset: e.target.value, layouts: p.layouts })
            }}
          >
            {Object.entries(PRESETS).map(([key, p]) => (
              <option key={key} value={key} title={p.description}>
                {p.name}
              </option>
            ))}
            {saved.preset === 'custom' && (
              <option value="custom">Custom (saved)</option>
            )}
          </select>
        </label>
        <select
          aria-label="Add a panel"
          value=""
          disabled={!missing.length}
          onChange={(e) =>
            update({
              layouts: addPanel(saved.layouts, e.target.value as PanelId),
              preset: 'custom',
            })
          }
        >
          <option value="">+ Add panel</option>
          {missing.map((p) => (
            <option key={p} value={p}>
              {PANEL_TITLES[p]}
            </option>
          ))}
        </select>
        <button
          className={saved.locked ? 'selected' : ''}
          onClick={() => update({ locked: !saved.locked })}
        >
          {saved.locked ? '🔒 Locked' : '🔓 Editing'}
        </button>
        <button
          className={saved.explain ? 'selected' : ''}
          onClick={() => update({ explain: !saved.explain })}
        >
          💡 Explain
        </button>
        <button onClick={() => setHelp(true)} title="Keyboard shortcuts">
          ⌨ Shortcuts
        </button>
        <span className="muted hint">
          {saved.locked
            ? 'Layout locked.'
            : 'Drag a panel by its title, resize from its corner.'}
        </span>
        {children}
      </div>
      <div ref={containerRef} className="ws-grid">
        {mounted && (
          <ResponsiveGridLayout
            width={width}
            layouts={saved.layouts}
            breakpoints={BREAKPOINTS}
            cols={COLS}
            rowHeight={30}
            margin={[12, 12]}
            dragConfig={{
              enabled: !saved.locked,
              handle: '.panel-title',
              cancel: 'input,button,select,label,a',
            }}
            resizeConfig={{ enabled: !saved.locked, handles: ['se'] }}
            onLayoutChange={(_layout, layouts) => {
              if (
                JSON.stringify(layouts.lg) !== JSON.stringify(saved.layouts.lg)
              )
                update({ layouts, preset: 'custom' })
            }}
          >
            {grid}
          </ResponsiveGridLayout>
        )}
      </div>
      {help && (
        <div className="dialog-backdrop" onClick={() => setHelp(false)}>
          <div
            className="dialog"
            role="dialog"
            aria-label="Keyboard shortcuts"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="panel-title">
              Keyboard shortcuts{' '}
              <button onClick={() => setHelp(false)}>Close</button>
            </div>
            <table className="compact">
              <tbody>
                {SHORTCUTS.map((s) => (
                  <tr key={s.action}>
                    <td>
                      <kbd>{s.keys}</kbd>
                    </td>
                    <td>{s.text}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </WorkspaceContext.Provider>
  )
}
