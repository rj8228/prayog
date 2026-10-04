import { adminApi } from '../api/rest'
import type { AccountView } from '../api/types'
import { useAction } from './useAdmin'

export function AccountsPanel({
  accounts,
  onChange,
}: {
  accounts: AccountView[] | null
  onChange: () => void
}) {
  const { status, run } = useAction()
  const act = (label: string, action: (t: string) => Promise<unknown>) =>
    void run(label, action).then(onChange)
  return (
    <section className="panel">
      <div className="panel-title">Accounts</div>
      {status && (
        <p className={status.ok ? 'result ok' : 'result bad'}>{status.text}</p>
      )}
      <div className="scroll">
        <table className="compact">
          <thead>
            <tr>
              <th>user</th>
              <th>account</th>
              <th>open</th>
              <th>requests</th>
              <th>rejected</th>
              <th>last seen</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {(accounts ?? []).map(({ account: a, openOrders }) => (
              <tr key={a.accountId}>
                <td>
                  {a.username}
                  <div className="muted small-print">{a.clientId}</div>
                </td>
                <td>
                  {a.label}
                  <div className="muted small-print">
                    {String(a.accountId).slice(0, 8)}…
                  </div>
                </td>
                <td>{openOrders}</td>
                <td>{a.requests}</td>
                <td className={a.rejected ? 'down' : ''}>{a.rejected}</td>
                <td className="muted">
                  {new Date(a.lastSeen).toLocaleTimeString()}
                </td>
                <td className="actions">
                  <button
                    className="small"
                    onClick={() =>
                      act(`Cancel all for ${a.username}/${a.label}`, (t) =>
                        adminApi.cancelAll(t, a.accountId),
                      )
                    }
                  >
                    Cancel all
                  </button>
                  <button
                    className="small"
                    onClick={() =>
                      act(`Disable ${a.username}/${a.label}`, (t) =>
                        adminApi.setEnabled(t, a.accountId, false),
                      )
                    }
                  >
                    Disable
                  </button>
                  <button
                    className="small"
                    onClick={() =>
                      act(`Enable ${a.username}/${a.label}`, (t) =>
                        adminApi.setEnabled(t, a.accountId, true),
                      )
                    }
                  >
                    Enable
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="muted small-print">
        Disable is the per-account kill switch: open orders are cancelled and
        new ones refused.
      </p>
    </section>
  )
}
