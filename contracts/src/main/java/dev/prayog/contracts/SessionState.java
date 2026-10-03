package dev.prayog.contracts;

/** Market session state. Orders are accepted only while OPEN. */
public enum SessionState {
    CLOSED,
    OPEN,
    HALTED
}
