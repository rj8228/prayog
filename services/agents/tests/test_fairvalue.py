import numpy as np
from prayog_agents.fairvalue import FairValue
from prayog_agents.scenarios import CALM, VOLATILE


def path(seed, scenario=CALM, steps=2_000):
    fv = FairValue(150_000, 135_000, 165_000, scenario, np.random.default_rng(seed))
    return [fv.step() for _ in range(steps)]


def test_same_seed_same_path():
    assert path(7) == path(7)
    assert path(7) != path(8)


def test_stays_well_inside_the_band():
    for seed in range(20):
        values = path(seed, VOLATILE, 5_000)
        assert min(values) >= 135_000 + 0.25 * 30_000 - 1
        assert max(values) <= 165_000 - 0.25 * 30_000 + 1


def test_it_actually_moves():
    values = path(3, CALM, 2_000)
    assert max(values) - min(values) > 0.005 * 150_000  # at least half a percent over ~17 minutes


def test_volatile_moves_more_than_calm():
    def spread(scenario):
        return np.std(np.diff(np.log(path(11, scenario, 3_000))))

    assert spread(VOLATILE) > 2 * spread(CALM)
