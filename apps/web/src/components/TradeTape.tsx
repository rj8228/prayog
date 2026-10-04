import { useAppSelector } from '../hooks'
import { rupees, simClock } from '../market/format'

export function TradeTape({ symbol }: { symbol: string }) {
  const trades = useAppSelector((s) => s.market.books[symbol]?.trades ?? [])
  const recent = trades.slice(-30).reverse()
  return (
    <section className="panel tape">
      <div className="panel-title">Trades</div>
      <table>
        <tbody>
          {recent.map((t) => (
            <tr
              key={t.tradeId}
              className={t.aggressor === 'BUY' ? 'up' : 'down'}
            >
              <td className="muted">{simClock(t.simTime)}</td>
              <td className="px">{rupees(t.price)}</td>
              <td className="qty">{t.quantity}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
