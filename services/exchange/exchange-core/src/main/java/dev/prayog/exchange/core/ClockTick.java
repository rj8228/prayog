package dev.prayog.exchange.core;

/**
 * Advances the engine's sim time (microseconds since the epoch). The only way time enters the engine: a clock thread
 * outside it reads the wall clock and sends ticks, which are journaled like orders, so a replay sees the same times.
 */
public record ClockTick(long simTime) implements Command {}
