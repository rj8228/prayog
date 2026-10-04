# 12. Trading workspace and browser strategies

Date: 2026-10-05

## Status

Accepted

## Context

Step 1.5 asked for a trading screen whose panels can be resized, dragged and swapped, more visual panels, and the
ability to run strategies against the live market from the browser, for any trader.

## Decisions

1. **react-grid-layout v2** (approved dependency) for the workspace: drag by the panel title, resize from the
   corner, responsive breakpoints (one column on phones). It pushes panels aside on drag; an explicit **swap**
   (each takes the other's position and size) gives the exact interchange. Four presets; the layout, lock, explain
   mode and one-click settings are saved per user (token subject) in `localStorage`, guarded because storage can
   be unavailable.
2. **Shared workspace state** (selected symbol, clicked price, ticket side, settings) goes through a React
   context; **account state** (open orders, fills, positions, notifications) is a Redux slice fed once by the
   private feed and the exchange's open-order list, so every panel sees the same data.
3. **Positions and P&L in the browser** use the average-cost method over this session's fills, deduplicated by
   trade id. Labelled an estimate until the post-trade service (S16) owns the official numbers.
4. **One-click trading** from the order book (ask buys, bid sells) with an optional confirm; fills raise a
   notification from the private feed, so the order answer only notifies when it did not fill.
5. **Strategies are pure `decide(context) → actions` functions** (TWAP, iceberg, grid, mean reversion, momentum,
   market maker). A `Runner` ticks once a second: reads the market from the store and its open orders from the
   exchange, vets each action, sends it, and tracks position from fills (the order answer or the private feed,
   whichever comes first, counted once by trade id).
6. **One account per strategy and symbol** (`algo-<strategy>-<symbol>` via `X-Prayog-Account`): orders, fills and
   P&L never mix with the user's own; one of each at a time; leftovers from a closed tab are cancelled at start.
7. **Client-side risk checks** before every order: order size, worst-case position (counting open orders on that
   side as filled), distance from the last trade, orders per minute; at the loss limit the strategy cancels all and
   stops. Cancels are always allowed. The exchange's own checks (bands, rate limit, kill switch) still apply.
8. **Runs in the tab, on the main thread**, not a Web Worker: a decision per second is tiny, and the runner needs the
   store and the token renewal that live there. Closing the tab sends a `keepalive` cancel-all per strategy
   account. Strategies use the wall clock: they are clients; the exchange's simulated clock rule is about the
   engine.

## Testing

- Unit: average-cost positions (averaging, partial close, flips, flat); layout swap, add and remove, presets fit the
  grid without overlap; shortcuts; depth window; each strategy's decisions; risk checks (each limit, cancels
  allowed, rate window); the runner with a fake exchange (fills counted once, refusals logged and not sent, loss
  limit cancels and stops, `done`, errors survived). Planted bugs (inventory lean sign, open orders ignored in the
  position check) were caught.
- Browser, on the live market: drag, resize, swap, presets, add panel, reload keeps the layout; one-click buy and
  sell with P&L −₹9.50 for a round trip across a ₹1.90 spread; all six strategies started (TWAP finished 60 shares in
  6 slices); a too-large order refused by risk; Stop all and closing the tab both left 0 open orders.

## Trade-offs

- Strategy P&L and positions live in the tab: a reload forgets them (orders are cancelled). Server-side strategies
  would survive, at the cost of running user code on the server.
- Background tabs throttle timers, so a strategy may tick less often when the tab is hidden.
- Layouts are per browser, not per account across devices; a server-side preference store can come with S19.
