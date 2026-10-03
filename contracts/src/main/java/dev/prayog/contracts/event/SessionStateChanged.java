package dev.prayog.contracts.event;

import dev.prayog.contracts.SessionState;
import java.util.Objects;

/** The market session moved to {@code state}. */
public record SessionStateChanged(long seq, long simTime, SessionState state) implements ExchangeEvent {

    public SessionStateChanged {
        Objects.requireNonNull(state, "state");
    }
}
