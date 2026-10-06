package dev.prayog.exchange.core.pipeline;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.EngineState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One reusable slot of the ring buffer. A producer writes the command; the matching handler writes the input sequence
 * and the events the command produced; downstream handlers only read. Slots are reused once every handler has passed
 * them, so a handler must copy anything it keeps.
 */
public final class CommandSlot {

    Command command;
    Object context;
    long inputSeq;
    final List<ExchangeEvent> events = new ArrayList<>();
    EngineState snapshot;

    /** The command, as submitted. */
    public Command command() {
        return command;
    }

    /**
     * Whatever the producer attached, such as the pending HTTP response waiting for this command's outcome. Never
     * journaled and never seen by the engine: it only lets a later stage find who to answer.
     */
    public Object context() {
        return context;
    }

    /** Position of this command in the official input order, starting at 1. */
    public long inputSeq() {
        return inputSeq;
    }

    /** For a {@code TakeSnapshot} command, the engine's state right after it; otherwise null. */
    public EngineState snapshot() {
        return snapshot;
    }

    /** Events the engine produced for this command, in order. Valid only during the handler call. */
    public List<ExchangeEvent> events() {
        return Collections.unmodifiableList(events);
    }
}
