// Post-trade learns who is behind an account the first time that account asks for its P&L (ADR 0015). A strategy
// trades only through the exchange, so without this lookup its leaderboard row would show a hashed id.
import { postTradeApi } from '../api/rest'

export async function introduce(
  account: string,
  token: () => Promise<string>,
  pnl: (token: string, label: string) => Promise<unknown> = postTradeApi.pnl,
): Promise<void> {
  try {
    await pnl(await token(), account)
  } catch {
    // Names are cosmetic: never stop a strategy over them.
  }
}
