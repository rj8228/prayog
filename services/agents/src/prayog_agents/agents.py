"""The simulated traders. Each one is an ordinary API client with its own account, exactly like a
user's bot."""

import asyncio
import logging
import random

from prayog_sdk import Fill, OrderUpdate, PrayogClient, PrayogError, private_updates
from prayog_sdk.client import RateLimitedError

from prayog_agents.fairvalue import FairValue
from prayog_agents.market import Market
from prayog_agents.quoting import maker_quotes, momentum_signal
from prayog_agents.scenarios import Scenario

log = logging.getLogger(__name__)


class Agent:
    """Common plumbing: an account, its positions (from its own fills) and polite error handling."""

    def __init__(self, name: str, client: PrayogClient, market: Market) -> None:
        self.name = name
        self.client = client
        self.market = market
        self.position = dict.fromkeys(market.symbols, 0)
        self.fills = 0

    async def track(self) -> None:
        async for update in private_updates(self.client):
            if isinstance(update, Fill):
                sign = 1 if update.side == "BUY" else -1
                self.position[update.symbol] += sign * update.quantity
                self.fills += 1
            self.on_update(update)

    def on_update(self, update: OrderUpdate | Fill) -> None:
        """Hook for agents that track their own resting orders."""

    async def safely(self, call):
        """Runs one API call; backs off instead of crashing on rate limits or a busy exchange."""
        try:
            return await call
        except RateLimitedError:
            await asyncio.sleep(1)
        except PrayogError as e:
            log.debug("%s: %s", self.name, e)
            await asyncio.sleep(0.2)
        return None


class MarketMaker(Agent):
    """Keeps a ladder of bids and asks around the fair value on every symbol, skewed against its
    inventory."""

    def __init__(
        self,
        client: PrayogClient,
        market: Market,
        scenario: Scenario,
        fair: dict[str, FairValue],
        instruments: dict,
        lot: int,
    ) -> None:
        super().__init__("market-maker", client, market)
        self.scenario = scenario
        self.fair = fair
        self.instruments = instruments
        self.lot = lot
        # (symbol, side, level) -> [order_id, price, total_quantity, filled]
        self.orders: dict[tuple[str, str, int], list[int]] = {}

    def on_update(self, update: OrderUpdate | Fill) -> None:
        for key, order in list(self.orders.items()):
            if order[0] != update.order_id:
                continue
            if isinstance(update, Fill):
                order[3] += update.quantity
                if order[3] >= order[2]:
                    del self.orders[key]
            elif update.status in ("cancelled", "rejected"):
                del self.orders[key]

    async def run(self) -> None:
        await self.safely(self.client.cancel_all())
        step = 0
        while True:
            await asyncio.sleep(self.scenario.step_seconds)
            if not self.market.is_open:
                self.orders.clear()  # the close expired everything
                continue
            step += 1
            if step % 4 == 0:
                await self.reconcile()
            for symbol in self.market.symbols:
                await self.requote(symbol)

    async def reconcile(self) -> None:
        """Re-reads the exchange's view of our open orders. The private feed can deliver a fill
        before the
        response that told us the order id, so local bookkeeping drifts; the exchange is the source
        of truth."""
        open_orders = await self.safely(self.client.open_orders())
        if open_orders is None:
            return
        live = {o.order_id: o for o in open_orders}
        tracked = set()
        for key, order in list(self.orders.items()):
            current = live.get(order[0])
            if current is None:
                del self.orders[key]  # filled or cancelled while we weren't looking
                continue
            tracked.add(order[0])
            order[1], order[2], order[3] = current.price, current.quantity, current.filled_quantity
        for order_id in live.keys() - tracked:
            await self.safely(self.client.cancel(order_id))  # an order we lost track of

    async def requote(self, symbol: str) -> None:
        s = self.scenario
        inst = self.instruments[symbol]
        quotes = maker_quotes(
            self.fair[symbol].value,
            self.position[symbol],
            tick=inst.tick_size,
            lot=self.lot,
            half_spread_ticks=s.half_spread_ticks,
            levels=s.levels,
            level_step_ticks=s.level_step_ticks,
            quote_lots=s.quote_lots,
            skew_ticks_per_lot=s.skew_ticks_per_lot,
            max_inventory_lots=s.max_inventory_lots,
            band_low=inst.band_low,
            band_high=inst.band_high,
        )
        wanted = {(symbol, q.side, q.level): q for q in quotes}
        for key in [k for k in self.orders if k[0] == symbol and k not in wanted]:
            order = self.orders.pop(key)
            await self.safely(self.client.cancel(order[0]))
        # Move orders that step away from the other side first (asks up, bids down), then the
        # rest. Otherwise a bid moved up before our ask has moved can cross that ask, and
        # self-trade prevention cancels it.
        for key, quote in sorted(wanted.items(), key=lambda kv: 0 if self._moves_away(*kv) else 1):
            existing = self.orders.get(key)
            if existing and existing[1] == quote.price:
                continue
            if self._would_cross_own(symbol, quote):
                continue  # try again next step, once the other side has moved
            if existing:
                # Move the order: new price, new total = what already filled + a fresh quote size.
                result = await self.safely(
                    self.client.modify(existing[0], quote.price, existing[3] + quote.quantity)
                )
                if result is not None and result.status == "modified":
                    existing[1], existing[2] = quote.price, existing[3] + quote.quantity
                    continue
                self.orders.pop(key, None)
            result = await self.safely(
                self.client.place(symbol, quote.side, quote.quantity, price=quote.price)
            )
            if result is not None and result.status == "resting":
                self.orders[key] = [
                    result.order_id,
                    quote.price,
                    quote.quantity,
                    result.filled_quantity,
                ]

    def _moves_away(self, key: tuple[str, str, int], quote) -> bool:
        existing = self.orders.get(key)
        if existing is None:
            return False
        return quote.price > existing[1] if quote.side == "SELL" else quote.price < existing[1]

    def _would_cross_own(self, symbol: str, quote) -> bool:
        """True if the quote would trade against one of our own resting orders on the other side."""
        other = "SELL" if quote.side == "BUY" else "BUY"
        prices = [o[1] for k, o in self.orders.items() if k[0] == symbol and k[1] == other]
        if not prices:
            return False
        return quote.price >= min(prices) if quote.side == "BUY" else quote.price <= max(prices)


class NoiseTrader(Agent):
    """Trades at random times in random directions: the uninformed flow every real market has."""

    def __init__(
        self,
        name: str,
        client: PrayogClient,
        market: Market,
        scenario: Scenario,
        lot: int,
        rng: random.Random,
    ) -> None:
        super().__init__(name, client, market)
        self.scenario = scenario
        self.lot = lot
        self.rng = rng

    async def run(self) -> None:
        while True:
            await asyncio.sleep(self.rng.expovariate(1 / self.scenario.noise_mean_interval_seconds))
            if not self.market.is_open:
                continue
            symbol = self.rng.choice(self.market.symbols)
            quantity = self.rng.randint(1, self.scenario.noise_max_lots) * self.lot
            # Lean against an accumulated position so noise doesn't drift far in one direction.
            buy_probability = 0.5 - max(-0.3, min(0.3, self.position[symbol] / (50 * self.lot)))
            side = "BUY" if self.rng.random() < buy_probability else "SELL"
            await self.safely(self.client.place(symbol, side, quantity))


class MomentumTrader(Agent):
    """Buys when short-term prices rise above their longer average and sells when they fall: it
    amplifies moves."""

    def __init__(
        self,
        name: str,
        client: PrayogClient,
        market: Market,
        scenario: Scenario,
        lot: int,
        rng: random.Random,
    ) -> None:
        super().__init__(name, client, market)
        self.scenario = scenario
        self.lot = lot
        self.rng = rng

    async def run(self) -> None:
        s = self.scenario
        while True:
            await asyncio.sleep(2 + self.rng.random())
            if not self.market.is_open:
                continue
            for symbol in self.market.symbols:
                signal = momentum_signal(
                    self.market.fast[symbol].value,
                    self.market.slow[symbol].value,
                    s.momentum_threshold,
                )
                position_lots = self.position[symbol] / self.lot
                if signal > 0 and position_lots < s.momentum_max_lots:
                    await self.safely(self.client.buy_market(symbol, s.momentum_lots * self.lot))
                elif signal < 0 and position_lots > -s.momentum_max_lots:
                    await self.safely(self.client.sell_market(symbol, s.momentum_lots * self.lot))
                elif signal == 0 and position_lots != 0 and self.rng.random() < 0.2:
                    # No trend: take some of the position off.
                    side = "SELL" if position_lots > 0 else "BUY"
                    await self.safely(self.client.place(symbol, side, self.lot))
