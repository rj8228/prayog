package dev.prayog.exchange.core.journal;

import dev.prayog.exchange.core.EventSink;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.SessionSchedule;
import java.util.List;
import java.util.Objects;

/**
 * Everything needed to build an engine in its starting state. Written as the first record of the input journal, so a
 * replay needs the journal and nothing else: no config files that might have changed since.
 *
 * @param instruments listed symbols, in the order they were configured
 * @param schedule trading hours, or null when sessions change only by ops command
 */
public record EngineSetup(List<Instrument> instruments, SessionSchedule schedule) {

    public EngineSetup {
        instruments = List.copyOf(Objects.requireNonNull(instruments, "instruments"));
    }

    /** A fresh engine in this setup's starting state. */
    public MatchingEngine newEngine(EventSink sink) {
        return new MatchingEngine(instruments, schedule, sink);
    }
}
