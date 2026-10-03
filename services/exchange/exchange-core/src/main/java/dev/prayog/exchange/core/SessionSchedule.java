package dev.prayog.exchange.core;

import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Daily trading hours. The engine compares each clock tick with these times; it never reads a clock itself.
 *
 * <p>A fixed {@link ZoneOffset} (IST is +05:30 all year) rather than a time zone with daylight-saving rules, so the
 * mapping from sim time to "time of day" can never change between a live run and a replay.
 */
public record SessionSchedule(LocalTime open, LocalTime close, ZoneOffset offset) {

    private static final long MICROS_PER_DAY = 86_400_000_000L;

    public SessionSchedule {
        Objects.requireNonNull(open, "open");
        Objects.requireNonNull(close, "close");
        Objects.requireNonNull(offset, "offset");
        if (!open.isBefore(close)) {
            throw new IllegalArgumentException("open must be before close: " + open + " / " + close);
        }
    }

    /** Whether {@code simTime} (epoch microseconds) falls inside trading hours, open inclusive, close exclusive. */
    boolean isOpenAt(long simTime) {
        long microsOfDay = Math.floorMod(local(simTime), MICROS_PER_DAY);
        return microsOfDay >= micros(open) && microsOfDay < micros(close);
    }

    /** Local calendar day number of {@code simTime}. */
    long day(long simTime) {
        return Math.floorDiv(local(simTime), MICROS_PER_DAY);
    }

    /**
     * Identifies the scheduled period {@code simTime} is in: each day has a "closed" and an "open" period. Two ticks
     * with different periods have crossed at least one boundary.
     */
    long period(long simTime) {
        return day(simTime) * 2 + (isOpenAt(simTime) ? 1 : 0);
    }

    private long local(long simTime) {
        return simTime + offset.getTotalSeconds() * 1_000_000L;
    }

    private static long micros(LocalTime time) {
        return time.toNanoOfDay() / 1_000;
    }
}
