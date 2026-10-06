package dev.prayog.exchange.core;

/**
 * Asks for a snapshot of the engine at this exact point in the input (ADR 0016). Journaled like every command, so the
 * snapshot's input seq is recorded; the engine itself does nothing with it, and replay treats it as a no-op. The
 * pipeline copies the engine's state into the command's slot for later stages to save.
 */
public record TakeSnapshot() implements Command {}
