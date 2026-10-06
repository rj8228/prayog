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
import org.agrona.DirectBuffer;
import org.agrona.ExpandableDirectByteBuffer;

/**
 * Restart: rebuilds the engine from the input journal before the pipeline starts, so the market comes back exactly as
 * it was (same resting orders, same queue positions, same next ids).
 *
 * <p>The input journal is the source of truth (ADR 0007). Replay regenerates every event; any event the event log is
 * missing (a crash between the two flushes) is appended to it, so both logs agree again before trading resumes.
 * Every replayed event is also handed to {@code replayed}, so derived state (the market-data book, open-order views)
 * can be rebuilt from the same stream.
 *
 * <p><b>Verified, not trusted.</b> Every replayed event that the event log already holds must match it byte for byte.
 * A difference means the engine no longer reproduces its own history (a rule changed without {@code SetRules}, a
 * non-deterministic bug): recovery refuses to start rather than trade on a book that differs from what clients were
 * told. The event log is derived data; see the runbook for rebuilding it from the input journal.
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
        private final Snapshot snapshot;
        private final long replayedCommands;

        private Recovered(
                EngineSetup setup,
                MatchingEngine engine,
                SwitchingSink sink,
                long lastInputSeq,
                long lastEventSeq,
                long lastSimTime,
                long repairedEvents,
                Snapshot snapshot,
                long replayedCommands) {
            this.setup = setup;
            this.engine = engine;
            this.sink = sink;
            this.lastInputSeq = lastInputSeq;
            this.lastEventSeq = lastEventSeq;
            this.lastSimTime = lastSimTime;
            this.repairedEvents = repairedEvents;
            this.snapshot = snapshot;
            this.replayedCommands = replayedCommands;
        }

        /** The snapshot recovery started from, or null if it replayed the whole journal. */
        public Snapshot snapshot() {
            return snapshot;
        }

        /** Commands replayed (after the snapshot, if there was one). */
        public long replayedCommands() {
            return replayedCommands;
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
        return recover(dir, events, replayed, null);
    }

    /**
     * As {@link #recover(Path, Journal, Consumer)}, starting from {@code snapshot} (ADR 0016): the engine is restored
     * from it and only the input after its seq is replayed, so {@code replayed} sees only the events after it; the
     * caller restores its derived state from the snapshot first. A snapshot the event log has not reached (the log was
     * rebuilt or cut) is ignored and the whole journal is replayed.
     */
    public static Recovered recover(Path dir, Journal events, Consumer<ExchangeEvent> replayed, Snapshot snapshot)
            throws IOException {
        Objects.requireNonNull(replayed, "replayed");
        long eventLogEnd = events.lastSeq();
        Snapshot start = snapshot != null && snapshot.eventSeq() <= eventLogEnd ? snapshot : null;
        JournalCodec codec = new JournalCodec();
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(1024);
        SwitchingSink sink = new SwitchingSink();
        State state = new State();
        JournalTailer recorded =
                new JournalTailer(dir, JournalHandler.EVENTS, start == null ? 1 : start.eventSeq() + 1);
        RecordMatcher matcher = new RecordMatcher();

        sink.target = event -> {
            state.lastEventSeq = event.seq();
            if (event.seq() <= eventLogEnd) {
                int length = codec.encode(event, buffer, 0);
                matcher.expect(event, buffer, length);
                try {
                    recorded.poll(event.seq(), 1, matcher);
                } catch (IOException e) {
                    throw new IllegalStateException("could not read the event log", e);
                }
                matcher.check();
            } else {
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

        RecordVisitor apply = (seq, bytes, offset, length) -> {
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
            state.replayedCommands++;
        };
        if (start == null) {
            JournalReader.read(dir, JournalHandler.INPUT, apply);
        } else {
            // The setup is still record 0 of the input journal; the engine comes from the snapshot.
            try (JournalTailer setup = new JournalTailer(dir, JournalHandler.INPUT, JournalHandler.SETUP_SEQ)) {
                setup.poll(
                        JournalHandler.SETUP_SEQ,
                        1,
                        (seq, bytes, offset, length) -> state.setup = codec.decodeEngineSetup(bytes, offset));
            }
            if (state.setup == null) {
                throw new IllegalStateException("nothing to recover: the input journal in " + dir + " is empty");
            }
            state.engine =
                    MatchingEngine.restore(state.setup.instruments(), state.setup.schedule(), start.engine(), sink);
            state.lastInputSeq = start.inputSeq();
            state.lastEventSeq = start.eventSeq();
            state.lastSimTime = start.engine().simTime();
            try (JournalTailer input = new JournalTailer(dir, JournalHandler.INPUT, start.inputSeq() + 1)) {
                while (input.poll(Long.MAX_VALUE, 10_000, apply) > 0) {
                    // keep reading until the end of the journal
                }
            }
        }
        if (state.engine == null) {
            throw new IllegalStateException("nothing to recover: the input journal in " + dir + " is empty");
        }
        if (eventLogEnd > state.lastEventSeq) {
            throw new IllegalStateException("event log ends at seq " + eventLogEnd
                    + " but replaying the input journal only produces up to " + state.lastEventSeq);
        }
        recorded.close();
        events.flush();
        return new Recovered(
                state.setup,
                state.engine,
                sink,
                state.lastInputSeq,
                state.lastEventSeq,
                state.lastSimTime,
                state.repaired,
                start,
                state.replayedCommands);
    }

    /** Compares one recorded event-log record with the replayed event's encoding. */
    private static final class RecordMatcher implements RecordVisitor {
        private ExchangeEvent expected;
        private ExpandableDirectByteBuffer expectedBytes;
        private int expectedLength;
        private boolean seen;
        private String problem;

        void expect(ExchangeEvent event, ExpandableDirectByteBuffer bytes, int length) {
            expected = event;
            expectedBytes = bytes;
            expectedLength = length;
            seen = false;
            problem = null;
        }

        @Override
        public void onRecord(long seq, DirectBuffer buffer, int offset, int length) {
            seen = true;
            if (seq != expected.seq()) {
                problem = "the event log has seq " + seq + " where replay produced seq " + expected.seq();
            } else if (!sameBytes(buffer, offset, length)) {
                problem = "event " + seq + " differs: recorded " + new JournalCodec().decodeEvent(buffer, offset)
                        + ", replayed " + expected;
            }
        }

        private boolean sameBytes(DirectBuffer buffer, int offset, int length) {
            if (length != expectedLength) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (buffer.getByte(offset + i) != expectedBytes.getByte(i)) {
                    return false;
                }
            }
            return true;
        }

        void check() {
            if (!seen) {
                problem = "the event log has no record for seq " + expected.seq();
            }
            if (problem != null) {
                throw new IllegalStateException("replaying the input journal does not reproduce the event log: "
                        + problem + ". The engine's rules changed without a SetRules command, or replay is not"
                        + " deterministic. Refusing to start.");
            }
        }
    }

    private static final class State {
        EngineSetup setup;
        MatchingEngine engine;
        long lastInputSeq;
        long lastEventSeq;
        long lastSimTime;
        long repaired;
        long replayedCommands;
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
