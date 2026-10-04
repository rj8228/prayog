import { api } from '../api/rest'
import type { Side } from '../api/types'
import { notify } from '../account/accountSlice'
import { withToken } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { useAppDispatch, useAppSelector } from '../hooks'
import { rupees } from '../market/format'
import { levels } from '../market/marketSlice'
import { useWorkspace } from '../workspace/WorkspaceContext'

/**
 * Depth ladder: asks above (best at the bottom), bids below (best at the top). A click copies the price into the
 * ticket; with 1-click on, clicking an ask buys there and clicking a bid sells there.
 */
export function Ladder() {
  const { symbol, setPrice, settings, setSettings, placed } = useWorkspace()
  const { token } = useAuth()
  const dispatch = useAppDispatch()
  const book = useAppSelector((s) => s.market.books[symbol])
  const asks = levels(book, 'SELL', 12).reverse()
  const bids = levels(book, 'BUY', 12)
  const max = Math.max(1, ...asks.map((l) => l[1]), ...bids.map((l) => l[1]))
  const bestBid = bids[0]?.[0]
  const bestAsk = asks[asks.length - 1]?.[0]
  const spread = bestBid && bestAsk ? bestAsk - bestBid : null

  const click = async (price: number, side: 'bid' | 'ask') => {
    setPrice(price)
    if (!settings.oneClick || !token) return
    const orderSide: Side = side === 'ask' ? 'BUY' : 'SELL'
    const what = `${orderSide === 'BUY' ? 'Buy' : 'Sell'} ${settings.quantity} ${symbol} @ ${rupees(price)}`
    if (settings.confirm && !window.confirm(`${what}?`)) return
    try {
      const r = await withToken((t) =>
        api.place(t, {
          symbol,
          side: orderSide,
          type: 'LIMIT',
          price,
          quantity: settings.quantity,
        }),
      )
      // A fill brings its own notification from the private feed; say something only when it didn't fill.
      if (r.status !== 'filled')
        dispatch(
          notify({
            text: `${what}: ${r.status}${r.reason ? ` (${r.reason})` : ''}`,
            kind: r.status === 'rejected' ? 'error' : 'info',
          }),
        )
      placed()
    } catch (e) {
      dispatch(
        notify({ text: `${what}: ${(e as Error).message}`, kind: 'error' }),
      )
    }
  }

  const row = (side: 'bid' | 'ask') =>
    function Row([price, quantity, orders]: readonly [number, number, number]) {
      return (
        <tr
          key={`${side}-${price}`}
          className={side}
          onClick={() => void click(price, side)}
        >
          <td className="orders">{orders}</td>
          <td className="qty">
            <span
              className="bar"
              style={{ width: `${(quantity / max) * 100}%` }}
            />
            <span>{quantity.toLocaleString('en-IN')}</span>
          </td>
          <td className="px">{rupees(price)}</td>
        </tr>
      )
    }
  return (
    <section className="panel ladder">
      <div className="panel-title">
        Order book · {symbol}{' '}
        {book?.stale ? <span className="warn">resyncing</span> : null}
        {token && (
          <label className="toggle" title="Click an ask to buy, a bid to sell">
            <input
              type="checkbox"
              checked={settings.oneClick}
              onChange={(e) =>
                setSettings({ ...settings, oneClick: e.target.checked })
              }
            />
            1-click
          </label>
        )}
      </div>
      {settings.oneClick && token && (
        <div className="oneclick-bar">
          qty
          <input
            inputMode="numeric"
            value={settings.quantity}
            onChange={(e) =>
              setSettings({
                ...settings,
                quantity: Math.max(1, Number(e.target.value) || 1),
              })
            }
          />
          <label className="toggle">
            <input
              type="checkbox"
              checked={settings.confirm}
              onChange={(e) =>
                setSettings({ ...settings, confirm: e.target.checked })
              }
            />
            confirm
          </label>
        </div>
      )}
      <table>
        <thead>
          <tr>
            <th>orders</th>
            <th>quantity</th>
            <th>price</th>
          </tr>
        </thead>
        <tbody>{asks.map(row('ask'))}</tbody>
        <tbody>
          <tr className="spread">
            <td colSpan={3}>
              {spread !== null
                ? `spread ${rupees(spread)}`
                : 'no two-sided market'}
            </td>
          </tr>
        </tbody>
        <tbody>{bids.map(row('bid'))}</tbody>
      </table>
    </section>
  )
}
