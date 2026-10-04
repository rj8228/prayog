package dev.prayog.exchange.core.journal;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.EventSink;
import dev.prayog.exchange.core.MatchingEngine;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import org.agrona.ExpandableDirectByteBuffer;

/**
 * Restart: rebuilds the engine from the input journal before the pipeline starts, so the market comes back exactly as
 * it was (same resting orders, same queue positions, same next ids).
 *
 * <p>The input journal is the source of truth (ADR 0007). Replay regenerates every event; any event the event log is
 * missing (a crash between the two flushes) is appended to it, so both logs agree again before trading resumes.
 * Every replayed event is also handed to {@code replayed}, so derived state (the market-data book, open-order views)
 * can be rebuilt from the same stream.
 */
public final class JournalRecovery {

    private JournalRecovery() {}

    /** What recovery found and rebuilt. */
    public static final class Recovered {
        private final EngineSetup setup;
        private final MatchingEngine engine;
        private final SwitchingSink sink;
        private final long lastInputSeq;
        private final long lastEventSeq;
        private final long lastSimTime;
        private final long repairedEvents;

        private Recovered(
                EngineSetup setup,
                MatchingEngine engine,
                SwitchingSink sink,
                long lastInputSeq,
                long lastEventSeq,
                long lastSimTime,
                long repairedEvents) {
            this.setup = setup;
            this.engine = engine;
            this.sink = sink;
            this.lastInputSeq = lastInputSeq;
            this.lastEventSeq = lastEventSeq;
            this.lastSimTime = lastSimTime;
            this.repairedEvents = repairedEvents;
        }

        /** The setup recorded in the journal (it wins over current configuration). */
        public EngineSetup setup() {
            return setup;
        }

        /** Input sequence of the last journaled command; the pipeline continues after it. */
        public long lastInputSeq() {
            return lastInputSeq;
        }

        public long lastEventSeq() {
            return lastEventSeq;
        }

        /** Sim time of the last journaled clock tick, or 0 if there was none. The clock resumes from here. */
        public long lastSimTime() {
            return lastSimTime;
        }

        /** Events that were missing from the event log and have been appended to it. */
        public long repairedEvents() {
            return repairedEvents;
        }

        /**
         * The pipeline's engine factory: hands over the rebuilt engine, now emitting into the live sink. Call once.
         */
        public Function<EventSink, MatchingEngine> engineFactory() {
            return live -> {
                sink.target = live;
                return engine;
            };
        }
    }

    /**
     * Replays the input journal in {@code dir} into a fresh engine.
     *
     * @param events the open event log; missing events are appended to it and flushed
     * @param replayed receives every event replay produces, in order
     * @throws IllegalStateException if the input journal is empty (nothing to recover) or the event log holds events
     *     the input journal cannot explain
     */
    public static Recovered recover(Path dir, Journal events, Consumer<ExchangeEvent> replayed) throws IOException {
        Objects.requireNonNull(replayed, "replayed");
        long eventLogEnd = events.lastSeq();
        JournalCodec codec = new JournalCodec();
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(1024);
        SwitchingSink sink = new SwitchingSink();
        State state = new State();

        sink.target = event -> {
            state.lastEventSeq = event.seq();
            if (event.seq() > eventLogEnd) {
                try {
                    int length = codec.encode(event, buffer, 0);
                    events.append(event.seq(), buffer, 0, length);
                    state.repaired++;
                } catch (IOException e) {
                    throw new IllegalStateException("could not repair the event log", e);
                }
            }
            replayed.accept(event);
        };

        JournalReader.read(dir, JournalHandler.INPUT, (seq, bytes, offset, length) -> {
            if (state.engine == null) {
                state.setup = codec.decodeEngineSetup(bytes, offset);
                state.engine = state.setup.newEngine(sink);
                return;
            }
            if (seq != state.lastInputSeq + 1) {
                throw new IllegalStateException(
                        "input journal has a gap: expected seq " + (state.lastInputSeq + 1) + ", found " + seq);
            }
            Command command = codec.decodeCommand(bytes, offset);
            if (command instanceof ClockTick tick) {
                state.lastSimTime = Math.max(state.lastSimTime, tick.simTime());
            }
            state.engine.apply(command);
            state.lastInputSeq = seq;
        });
        if (state.engine == null) {
            throw new IllegalStateException("nothing to recover: the input journal in " + dir + " is empty");
        }
        if (eventLogEnd > state.lastEventSeq) {
            throw new IllegalStateException("event log ends at seq " + eventLogEnd
                    + " but replaying the input journal only produces up to " + state.lastEventSeq);
        }
        events.flush();
        return new Recovered(
                state.setup,
                state.engine,
                sink,
                state.lastInputSeq,
                state.lastEventSeq,
                state.lastSimTime,
                state.repaired);
    }

    private static final class State {
        EngineSetup setup;
        MatchingEngine engine;
        long lastInputSeq;
        long lastEventSeq;
        long lastSimTime;
        long repaired;
    }

    /**
     * The engine's sink is fixed at construction, but its events must go to the replay first and to the pipeline
     * after. Set before the matching thread starts, then only read by it (thread start publishes the write).
     */
    private static final class SwitchingSink implements EventSink {
        EventSink target;

        @Override
        public void accept(ExchangeEvent event) {
            target.accept(event);
        }
    }
}
