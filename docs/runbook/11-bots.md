# 11. Bots

Building your own: [docs/bots/](../bots/README.md). Running and checking the ones that exist:

| Step | Command | Expect |
|---|---|---|
| Simulated traders running | `make logs SERVICE=agents` | Every 30 s one line per symbol: fair value, last price, best bid/ask, trades, market-maker inventory |
| Switch scenario | Set `AGENTS_SCENARIO=volatile` in `.env`, then `make up` | Wider spreads, bigger and more frequent moves |
| Market maker's orders | `PRAYOG_CLIENT_ID=prayog-agents PRAYOG_CLIENT_SECRET=$(grep PRAYOG_AGENTS_SECRET .env \| cut -d= -f2) uv run prayog --account mm orders` | About 6 orders per symbol (3 bids, 3 asks) |
| Sample bot | `uv run python sdk/python/examples/sample_bot.py --symbol INFY --seconds 120` | Log lines for its orders and fills; final position printed; it cancels its orders on exit |
| Your bot's view | `uv run prayog --account <label> orders` | Exactly the orders your bot thinks it has |

Order outcomes across all bots: `curl -s http://api.prayog.localhost/actuator/prometheus | grep prayog_orders_total`.
A climbing `outcome="cancelled"` count for `kind="modify"` means someone is cancelling themselves via self-trade
prevention.
