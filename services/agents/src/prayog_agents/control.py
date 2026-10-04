"""Follows the admin console's live controls (GET /api/v1/simulation): scenario, pause and price
jumps."""

import asyncio
import logging

from prayog_sdk import PrayogClient, PrayogError

from prayog_agents.fairvalue import FairValue
from prayog_agents.market import Market
from prayog_agents.scenarios import SCENARIOS

log = logging.getLogger(__name__)


class SimulationFollower:
    """Applies each new control state once: ``version`` says something changed, jump ``id``s are
    applied once."""

    def __init__(self, market: Market, fair: dict[str, FairValue], agents: list) -> None:
        self.market = market
        self.fair = fair
        self.agents = agents
        self.version = -1
        self.applied_jumps: set[int] = set()
        self.first = True

    def apply(self, state: dict) -> list[str]:
        """Applies a control state; returns what changed (for the log)."""
        changes: list[str] = []
        if state["version"] == self.version:
            return changes
        self.version = state["version"]
        name = state.get("scenario")
        if name and name in SCENARIOS:
            scenario = SCENARIOS[name]
            if any(a.scenario is not scenario for a in self.agents if hasattr(a, "scenario")):
                for agent in self.agents:
                    if hasattr(agent, "scenario"):
                        agent.scenario = scenario
                for value in self.fair.values():
                    value.scenario = scenario
                changes.append(f"scenario {name}")
        if state.get("paused", False) != self.market.paused:
            self.market.paused = state.get("paused", False)
            changes.append("paused" if self.market.paused else "resumed")
        for jump in state.get("jumps", []):
            if jump["id"] in self.applied_jumps:
                continue
            self.applied_jumps.add(jump["id"])
            if self.first:
                continue  # jumps made before we started are history, not news
            targets = [jump["symbol"]] if jump.get("symbol") else list(self.fair)
            for symbol in targets:
                if symbol in self.fair:
                    self.fair[symbol].jump(jump["percent"])
            changes.append(f"jump {jump['percent']:+.2f}% {jump.get('symbol') or 'all symbols'}")
        self.first = False
        return changes

    async def run(self, client: PrayogClient, maker) -> None:
        while True:
            try:
                state = await client._request("GET", "/api/v1/simulation")
                was_paused = self.market.paused
                for change in self.apply(state):
                    log.info("admin control: %s", change)
                if self.market.paused and not was_paused:
                    await maker.client.cancel_all()  # a paused market maker shows no quotes
            except PrayogError as e:
                log.debug("simulation control unavailable: %s", e)
            await asyncio.sleep(1)
