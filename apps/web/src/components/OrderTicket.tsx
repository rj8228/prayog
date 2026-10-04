import { useState } from 'react'
import { ApiError, api } from '../api/rest'
import type { Side } from '../api/types'
import { signIn, withToken } from '../auth/auth'
import { useAuth } from '../auth/useAuth'
import { rupees, toPaise } from '../market/format'
import { orderLatencyMs } from '../market/latency'

export function OrderTicket({
  symbol,
  price,
  onPlaced,
}: {
  symbol: string
  price: number | null
  onPlaced: () => void
}) {
  const { token } = useAuth()
  const [side, setSide] = useState<Side>('BUY')
  const [type, setType] = useState<'LIMIT' | 'MARKET'>('LIMIT')
  const [priceText, setPriceText] = useState('')
  const [quantity, setQuantity] = useState('10')
  const [result, setResult] = useState<{ ok: boolean; text: string } | null>(
    null,
  )
  const [busy, setBusy] = useState(false)

  // Clicking a price in the ladder fills it in (state adjusted during render when the prop changes).
  const [seenPrice, setSeenPrice] = useState(price)
  if (price !== seenPrice) {
    setSeenPrice(price)
    if (price !== null) setPriceText((price / 100).toFixed(2))
  }

  if (!token) {
    return (
      <section className="panel ticket">
        <div className="panel-title">Place an order</div>
        <p className="muted">
          Watching is open to everyone. Sign in as a trader to place orders.
        </p>
        <button className="primary" onClick={() => void signIn()}>
          Sign in
        </button>
      </section>
    )
  }

  const submit = async () => {
    const qty = Number(quantity)
    const paise = type === 'LIMIT' ? toPaise(priceText) : undefined
    if (!Number.isInteger(qty) || qty <= 0)
      return setResult({ ok: false, text: 'Quantity must be a whole number' })
    if (type === 'LIMIT' && paise === null)
      return setResult({ ok: false, text: 'Price like 1495.50' })
    setBusy(true)
    const started = performance.now()
    try {
      const r = await withToken((t) =>
        api.place(t, {
          symbol,
          side,
          type,
          quantity: qty,
          price: paise ?? undefined,
        }),
      )
      const latency = Math.round(performance.now() - started)
      if (r.orderId) orderLatencyMs.set(r.orderId, latency)
      const prices = r.fills.map((f) => rupees(f.price)).join(', ')
      // e.g. "filled 10 @ 1,509.10", "resting, 15 open", "cancelled (NO_LIQUIDITY), filled 3 @ 1,500.00"
      let head =
        r.status === 'filled'
          ? `filled ${r.filledQuantity} @ ${prices}`
          : r.status
      if (r.reason) head += ` (${r.reason})`
      const parts = [head]
      if (r.status !== 'filled' && r.filledQuantity)
        parts.push(`filled ${r.filledQuantity} @ ${prices}`)
      if (r.leavesQuantity) parts.push(`${r.leavesQuantity} open`)
      setResult({
        ok: r.status !== 'rejected',
        text: `${parts.join(', ')} · ${latency} ms`,
      })
      onPlaced()
    } catch (e) {
      setResult({
        ok: false,
        text:
          e instanceof ApiError
            ? `${e.status}: ${e.message}`
            : (e as Error).message,
      })
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="panel ticket">
      <div className="panel-title">Place an order: {symbol}</div>
      <div className="segmented">
        <button
          className={side === 'BUY' ? 'buy selected' : 'buy'}
          onClick={() => setSide('BUY')}
        >
          Buy
        </button>
        <button
          className={side === 'SELL' ? 'sell selected' : 'sell'}
          onClick={() => setSide('SELL')}
        >
          Sell
        </button>
      </div>
      <div className="segmented">
        <button
          className={type === 'LIMIT' ? 'selected' : ''}
          onClick={() => setType('LIMIT')}
        >
          Limit
        </button>
        <button
          className={type === 'MARKET' ? 'selected' : ''}
          onClick={() => setType('MARKET')}
        >
          Market
        </button>
      </div>
      <label>
        Quantity
        <input
          inputMode="numeric"
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
        />
      </label>
      {type === 'LIMIT' && (
        <label>
          Price (₹)
          <input
            inputMode="decimal"
            value={priceText}
            onChange={(e) => setPriceText(e.target.value)}
          />
        </label>
      )}
      <button
        className={side === 'BUY' ? 'buy-action' : 'sell-action'}
        disabled={busy}
        onClick={() => void submit()}
      >
        {side === 'BUY' ? 'Buy' : 'Sell'} {symbol}
      </button>
      {result && (
        <p className={result.ok ? 'result ok' : 'result bad'}>{result.text}</p>
      )}
    </section>
  )
}
