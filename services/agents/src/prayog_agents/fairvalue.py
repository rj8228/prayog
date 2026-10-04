"""The hidden "true" price each simulated trader reacts to. Seeded, so a scenario is repeatable."""

import math

import numpy as np

from prayog_agents.scenarios import Scenario


class FairValue:
    """Mean-reverting random walk with jumps, in log price, kept inside the instrument's price band.

    ``step()`` advances one step; the value is a float in paise (orders are rounded to the tick
    when sent).
    """

    def __init__(
        self,
        reference: int,
        band_low: int,
        band_high: int,
        scenario: Scenario,
        rng: np.random.Generator,
    ) -> None:
        self.reference = reference
        self.scenario = scenario
        self.rng = rng
        self.log_ref = math.log(reference)
        # Stay well inside the band so quotes around the fair value are never rejected.
        margin = 0.25 * (band_high - band_low)
        self.log_low = math.log(band_low + margin)
        self.log_high = math.log(band_high - margin)
        self.log_value = self.log_ref

    @property
    def value(self) -> float:
        return math.exp(self.log_value)

    def step(self) -> float:
        s = self.scenario
        drift = s.reversion * (self.log_ref - self.log_value)
        shock = s.volatility * self.rng.standard_normal()
        jump = 0.0
        if self.rng.random() < s.jump_probability:
            jump = s.jump_size * (1 if self.rng.random() < 0.5 else -1)
        self.log_value = min(
            self.log_high, max(self.log_low, self.log_value + drift + shock + jump)
        )
        return self.value
