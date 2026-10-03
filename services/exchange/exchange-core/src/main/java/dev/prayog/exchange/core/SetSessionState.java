package dev.prayog.exchange.core;

import dev.prayog.contracts.SessionState;
import java.util.Objects;

/** Ops moves the market to a new session state (open, halt, close). */
public record SetSessionState(SessionState state) implements Command {

    public SetSessionState {
        Objects.requireNonNull(state, "state");
    }
}
