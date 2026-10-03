package dev.prayog.exchange.core;

/**
 * An input to the engine. Every change to engine state comes from a command, so replaying the same commands in the
 * same order rebuilds the same state.
 *
 * <p>Sealed so a {@code switch} over commands must handle every type.
 */
public sealed interface Command
        permits NewOrder, CancelOrder, ModifyOrder, ClockTick, SetSessionState, SetAccountEnabled {}
