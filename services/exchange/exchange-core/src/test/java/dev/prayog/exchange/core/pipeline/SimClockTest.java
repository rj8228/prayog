package dev.prayog.exchange.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SimClockTest {

    private static final long START = 1_790_000_000_000_000L;
    private final AtomicLong wall = new AtomicLong(5_000_000_000L); // fake System.nanoTime()

    @Test
    void startsAtTheGivenSimTime() {
        assertThat(new SimClock(START, 1, wall::get).now()).isEqualTo(START);
    }

    @Test
    void advancesWithWallTimeTimesTheMultiplier() {
        SimClock clock = new SimClock(START, 10, wall::get);
        wall.addAndGet(1_500_000); // 1.5 ms of wall time

        assertThat(clock.now()).isEqualTo(START + 15_000); // 15 ms of sim time, in microseconds
    }

    @Test
    void changingTheMultiplierNeverMakesTimeJump() {
        SimClock clock = new SimClock(START, 1, wall::get);
        wall.addAndGet(2_000_000); // 2 ms at 1x
        long before = clock.now();
        clock.setMultiplier(60);

        assertThat(clock.now()).isEqualTo(before);
        wall.addAndGet(1_000_000); // 1 ms at 60x
        assertThat(clock.now()).isEqualTo(before + 60_000);
    }

    @Test
    void multiplierMustBeInRange() {
        assertThatThrownBy(() -> new SimClock(START, 0, wall::get)).isInstanceOf(IllegalArgumentException.class);
        SimClock clock = new SimClock(START, 1, wall::get);
        assertThatThrownBy(() -> clock.setMultiplier(SimClock.MAX_MULTIPLIER + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void advanceToJumpsForwardAndKeepsRunning() {
        SimClock clock = new SimClock(START, 2, wall::get);
        clock.advanceTo(START + 1_000_000);
        wall.addAndGet(1_000_000); // 1 ms of wall time at 2x

        assertThat(clock.now()).isEqualTo(START + 1_000_000 + 2_000);
    }

    @Test
    void advanceToNeverMovesBackwards() {
        SimClock clock = new SimClock(START, 1, wall::get);
        wall.addAndGet(5_000_000);
        clock.advanceTo(START);

        assertThat(clock.now()).isEqualTo(START + 5_000);
    }
}
