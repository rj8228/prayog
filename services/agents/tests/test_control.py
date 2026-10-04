import numpy as np
from prayog_agents.control import SimulationFollower
from prayog_agents.fairvalue import FairValue
from prayog_agents.scenarios import CALM, VOLATILE


class FakeMarket:
    paused = False


class FakeAgent:
    def __init__(self):
        self.scenario = CALM


def setup():
    fair = {"INFY": FairValue(150_000, 135_000, 165_000, CALM, np.random.default_rng(1))}
    agents = [FakeAgent(), FakeAgent()]
    return fair, agents, FakeMarket(), SimulationFollower(FakeMarket(), fair, agents)


def state(version, **kw):
    return {"version": version, "scenario": None, "paused": False, "jumps": [], **kw}


def test_old_jumps_are_history_and_new_ones_apply_once():
    fair, _agents, _, follower = setup()
    follower.apply(state(1, jumps=[{"id": 1, "symbol": "INFY", "percent": -3.0}]))
    assert abs(fair["INFY"].value - 150_000) < 1  # made before we started: ignored
    follower.apply(
        state(
            2,
            jumps=[
                {"id": 1, "symbol": "INFY", "percent": -3.0},
                {"id": 2, "symbol": "INFY", "percent": 2.0},
            ],
        )
    )
    assert abs(fair["INFY"].value - 153_000) < 1
    follower.apply(state(3, jumps=[{"id": 2, "symbol": "INFY", "percent": 2.0}]))
    assert abs(fair["INFY"].value - 153_000) < 1  # not applied twice


def test_scenario_and_pause_switch_live():
    fair, agents, _, follower = setup()
    changes = follower.apply(state(5, scenario="volatile", paused=True))
    assert all(a.scenario is VOLATILE for a in agents)
    assert fair["INFY"].scenario is VOLATILE
    assert follower.market.paused
    assert "scenario volatile" in changes and "paused" in changes
    assert follower.apply(state(5, scenario="calm")) == []  # same version: nothing to do


def test_jumps_stay_inside_the_band():
    fair, _, _, _ = setup()
    for _ in range(10):
        fair["INFY"].jump(8.0)
    assert fair["INFY"].value <= 165_000 - 0.25 * 30_000 + 1
