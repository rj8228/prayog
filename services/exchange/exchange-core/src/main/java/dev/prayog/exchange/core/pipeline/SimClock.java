package dev.prayog.exchange.core.pipeline;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Turns wall time into sim time: {@code simTime = anchor + wallElapsed × multiplier} (BUILD_PLAN 16.1 #16).
 *
 * <p>The only place in the exchange that reads a real clock, and it lives outside the engine: its readings reach the
 * engine as {@code ClockTick} commands, which are journaled, so a replay never reads the wall clock at all.
 *
 * <p>Changing the multiplier re-anchors at the current sim time, so time speeds up or slows down without jumping.
 * Thread-safe: the clock thread reads it while ops may change the multiplier.
 */
public final class SimClock {

    public static final int MAX_MULTIPLIER = 10_000;

    private final LongSupplier wallNanos;
    private long anchorSimMicros;
    private long anchorWallNanos;
    private int multiplier;

    /**
     * @param startSimMicros sim time at the moment of construction (for example today's 09:15 IST)
     * @param wallNanos the wall clock, normally {@code System::nanoTime}; a fake in tests
     */
    public SimClock(long startSimMicros, int multiplier, LongSupplier wallNanos) {
        this.wallNanos = Objects.requireNonNull(wallNanos, "wallNanos");
        this.anchorSimMicros = startSimMicros;
        this.anchorWallNanos = wallNanos.getAsLong();
        this.multiplier = checked(multiplier);
    }

    /** Current sim time, microseconds since the epoch. */
    public synchronized long now() {
        return at(wallNanos.getAsLong());
    }

    public synchronized int multiplier() {
        return multiplier;
    }

    /** Changes speed from now on: 1 = real time, 10 = ten sim seconds per wall second. */
    public synchronized void setMultiplier(int newMultiplier) {
        long wall = wallNanos.getAsLong();
        anchorSimMicros = at(wall);
        anchorWallNanos = wall;
        multiplier = checked(newMultiplier);
    }

    /**
     * Jumps forward to {@code simMicros} (for example the next day's open) and keeps running at the same speed from
     * there. Never moves backwards: an earlier time is ignored, so ticks stay monotonic.
     */
    public synchronized void advanceTo(long simMicros) {
        long wall = wallNanos.getAsLong();
        anchorSimMicros = Math.max(at(wall), simMicros);
        anchorWallNanos = wall;
    }

    private long at(long wall) {
        return anchorSimMicros + (wall - anchorWallNanos) / 1_000 * multiplier;
    }

    private static int checked(int multiplier) {
        if (multiplier < 1 || multiplier > MAX_MULTIPLIER) {
            throw new IllegalArgumentException("multiplier must be 1.." + MAX_MULTIPLIER + ": " + multiplier);
        }
        return multiplier;
    }
}
