package dev.prayog.exchange.core;

/**
 * Switches the engine to matching-rules {@code version} from this point in the input stream on (ADR 0014).
 *
 * <p>Rule changes are commands so that history replays exactly: commands journaled before a {@code SetRules} are
 * replayed under the old rules, and those after it under the new ones. The exchange journals
 * {@code SetRules(MatchingEngine.LATEST_RULES)} each time it starts; it is a no-op when the engine is already there.
 */
public record SetRules(int version) implements Command {}
