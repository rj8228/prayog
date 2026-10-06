# 20. Rate-limit tiers: people, users' bots, the exchange's simulated traders

Date: 2026-10-07

## Status

Accepted

## Context

BUILD_PLAN 16.1 #22 set a token bucket per account with a higher limit for bots, and listed "a separate agent
rate-limit tier" as a Phase 2 item, pulled into M4. Until now every `bot` client shared one limit (500 orders/s), so a
user's bot could send as fast as the simulated market makers that keep the market liquid.

## Decisions

1. **Three tiers, chosen from the token:**

   | Tier | Who | Default |
   |---|---|---|
   | `trader` | people (no `bot` role) | 20/s, burst 40 |
   | `bot` | bots of users: any other `bot` client, e.g. `prayog-bot-demo` | 50/s, burst 100 |
   | `agent` | the exchange's simulated traders: OAuth clients listed in `agent-clients` (`prayog-agents`) | 500/s, burst 1,000 |

   The tier depends on the client (`azp`), which Keycloak sets and the token's signature protects. A user cannot
   choose it.
2. **Still per account.** Each account has its own bucket; the tier only sets its size and refill rate.
3. **Visible.** A refusal still counts in `prayog_orders_total{outcome="rate_limited"}`, and also in
   `prayog_rate_limited_total{tier}`, so one noisy tier is easy to see.

## Testing

- API: twenty orders at once from each tier (test limits: burst 5, 8 and 1,000): about 5 and 8 accepted, and all 20
  for the simulated traders.

## Trade-offs

- The agent list is configuration (`PRAYOG_EXCHANGE_RATE_LIMIT_AGENT_CLIENTS`), not a Keycloak role: one more role
  would also have to be kept out of users' hands. A role can replace it if hosted bots arrive (Phase 3).
