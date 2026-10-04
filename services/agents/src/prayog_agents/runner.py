"""Starts a simulated market: fair values, one market maker, noise and momentum traders.

Configured by environment variables:

    AGENTS_SCENARIO   calm (default) or volatile
    AGENTS_SEED       random seed (default 42): same seed, same fair-value paths and decisions
    AGENTS_SYMBOLS    comma-separated (default: every listed symbol)
    AGENTS_LOT        shares per lot (default 10)
    PRAYOG_API_URL, PRAYOG_AUTH_URL, PRAYOG_CLIENT_ID (prayog-agents), PRAYOG_CLIENT_SECRET

The orders still arrive over the network with real timing, so a seed reproduces the agents'
intentions, not the
exact session (BUILD_PLAN 16.1 #15); the exchange's journal is what reproduces a session.
"""

import asyncio
import dataclasses
import logging
import os
import random

import httpx
import numpy as np
from prayog_sdk import PrayogClient, Settings
from prayog_sdk.money import to_rupees

from prayog_agents.agents import MarketMaker, MomentumTrader, NoiseTrader
from prayog_agents.control import SimulationFollower
from prayog_agents.fairvalue import FairValue
from prayog_agents.market import Market
from prayog_agents.scenarios import SCENARIOS

log = logging.getLogger("prayog_agents")


async def wait_for_exchange(settings: Settings) -> list:
    async with PrayogClient(settings) as probe:
        while True:
            try:
                return await probe.instruments()
            except (httpx.HTTPError, OSError) as e:
                log.info("waiting for the exchange (%s)", e)
                await asyncio.sleep(2)


async def run() -> None:
    scenario = SCENARIOS[os.environ.get("AGENTS_SCENARIO", "calm")]
    seed = int(os.environ.get("AGENTS_SEED", "42"))
    lot = int(os.environ.get("AGENTS_LOT", "10"))
    base = Settings.from_env(client_id=os.environ.get("PRAYOG_CLIENT_ID", "prayog-agents"))

    instruments = {i.symbol: i for i in await wait_for_exchange(base)}
    wanted = os.environ.get("AGENTS_SYMBOLS")
    symbols = sorted(wanted.split(",") if wanted else instruments)
    log.info("scenario %s, seed %d, symbols %s", scenario.name, seed, ",".join(symbols))

    rng = np.random.default_rng(seed)
    fair = {
        s: FairValue(
            instruments[s].reference_price,
            instruments[s].band_low,
            instruments[s].band_high,
            scenario,
            np.random.default_rng(rng.integers(2**32)),
        )
        for s in symbols
    }
    market = Market(base, symbols)

    def account(label: str) -> PrayogClient:
        return PrayogClient(dataclasses.replace(base, account=label))

    maker = MarketMaker(account("mm"), market, scenario, fair, instruments, lot)
    agents = [maker]
    agents += [
        NoiseTrader(
            f"noise-{i}",
            account(f"noise-{i}"),
            market,
            scenario,
            lot,
            random.Random(seed * 100 + i),
        )
        for i in range(scenario.noise_traders)
    ]
    agents += [
        MomentumTrader(
            f"momentum-{i}",
            account(f"momentum-{i}"),
            market,
            scenario,
            lot,
            random.Random(seed * 1000 + i),
        )
        for i in range(scenario.momentum_traders)
    ]

    async def evolve() -> None:
        while True:
            await asyncio.sleep(scenario.step_seconds)
            for value in fair.values():
                value.step()

    async def report() -> None:
        while True:
            await asyncio.sleep(30)
            for s in symbols:
                book = market.books[s]
                log.info(
                    "%s fair %s last %s bid %s ask %s trades %d mm-inventory %d",
                    s,
                    to_rupees(round(fair[s].value)),
                    to_rupees(book.last_price or 0),
                    to_rupees(book.best_bid or 0),
                    to_rupees(book.best_ask or 0),
                    market.trades[s],
                    maker.position[s],
                )

    follower = SimulationFollower(market, fair, agents)
    tasks = [market.run(), evolve(), report(), follower.run(maker.client, maker)]
    for agent in agents:
        tasks += [agent.track(), agent.run()]
    await asyncio.gather(*tasks)


def main() -> None:
    logging.basicConfig(
        level=os.environ.get("LOG_LEVEL", "INFO"),
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
    )
    logging.getLogger("httpx").setLevel(logging.WARNING)
    asyncio.run(run())


if __name__ == "__main__":
    main()
