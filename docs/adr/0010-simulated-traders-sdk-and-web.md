# 10. Simulated traders, Python SDK and the web market page

Date: 2026-10-04

## Status

Accepted

## Context

S12 (SDK and sample bot) and S13 (simulated traders) make the market move without humans; a first slice of S18 lets
people watch and trade in a browser. All three must use only the public API, like any user's bot.

## Decisions

1. **Python SDK (`prayog_sdk`)**: async httpx client with client-credentials tokens refreshed 30 s before expiry and
   one retry after a 401; typed errors (`RateLimitedError`, `BusyError`, `NotFoundError`); `LocalBook` that raises
   `SequenceGap`; reconnecting market-data and private streams; paise/rupee helpers. A `prayog` CLI covers manual
   trading and inspection (`book`, `buy`, `orders`, `watch`...). It reads the repository's `.env` for the demo bot.
2. **Simulated traders (`prayog_agents`)**, one container, each agent its own account label under `prayog-agents`:
   - fair value per symbol: mean-reverting random walk in log price with jumps, seeded (numpy), kept inside the band;
   - a market maker quoting 3 levels each side around fair value, skewed against inventory, capped inventory;
   - noise traders (random market orders, leaning against their own position) and momentum traders (fast vs slow
     EMA of trade prices);
   - scenarios `calm` and `volatile` (`AGENTS_SCENARIO`), seed `AGENTS_SEED`.
3. **The market maker never self-trades**: when quotes move, orders stepping away from the opposite side move first
   (asks up before bids up), and a quote that would still reach its own opposite order waits a step. Found live: the
   naive order produced 798 self-trade cancels in a few minutes.
4. **Bots reconcile with the exchange**: a fill can reach the private feed before the REST answer that names the order,
   so local bookkeeping drifts. The market maker re-reads `GET /orders` every 2 s and treats it as the truth. Found
   live as a one-sided book.
5. **Web market page (React 19, Redux Toolkit, react-router 8)**: public market view (tickers, Lightweight Charts
   candles built from trades, depth ladder, trade tape) rebuilt exactly from the feed with gap detection and
   resubscribe; sign-in with `oidc-client-ts` (code + PKCE, tokens in session storage, silent renew); order ticket,
   open orders with cancel and cancel-all, live fills from the private feed. API calls renew the token once after a
   401. Served by nginx (non-root, port 8080) behind Traefik; `/api` on the same host goes to the exchange, so no
   CORS.
6. **Images**: `python:3.12.15-slim` + uv 0.12.22 with `uv sync --frozen --no-dev`; `node:22.23.3-alpine` build with
   `COREPACK_ENABLE_DOWNLOAD_PROMPT=0` (Corepack otherwise waits for a yes/no and the build hangs), served by
   `nginx:1.30.5-alpine`.

## Testing

- SDK: money, `LocalBook` (gaps, resets), client against a fake transport (headers, token reuse and refresh, typed
  errors), CLI `.env` loading. Planted: no seq check, caught.
- Agents: fair value reproducible by seed, inside the band, moves, volatile > calm; quotes on tick, inside band,
  never crossed, skewed by inventory, stop at the cap; EMA and momentum signal.
- Web: market slice (snapshot + deltas, gap marks stale, zero quantity removes), trade merging, candles, formatting,
  ladder rendering and click.
- Browser (Playwright, by hand): anonymous market view updates live; sign-in as `trader1`; limit order rests and
  shows in the ladder and My orders; market order fills; cancel and cancel-all.
- `make e2e`: two-sided 100% of a 30 s window on all symbols, trades on every symbol, bot round trip on the private
  feed.

## Trade-offs

- The sample bot and noise traders use market orders: simple, but they pay the spread.
- A seed reproduces the agents' intentions, not the session (network timing); the journal reproduces the session.
- The web app is a first slice: no P&L, blotter history or ops page yet (S16, S19).
