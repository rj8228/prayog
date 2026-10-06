package dev.prayog.exchange.core.journal;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import java.io.IOException;
import java.nio.file.Path;
import org.agrona.ExpandableDirectByteBuffer;

/**
 * Pipeline stage 1: records each command in the input journal and its events in the event log, and makes both durable
 * before any later stage sees the slot. Later stages (responses, market data) therefore only ever report what is on
 * disk: a client is never told about a trade that a crash could erase.
 *
 * <p>One fsync per Disruptor batch ({@code endOfBatch}): under load one disk flush covers many commands (group commit);
 * when quiet, each command gets its own flush and waits no longer than that.
 *
 * <p>The input journal is the source of truth. Its record 0 is the {@link EngineSetup}; command records carry the
 * input sequence. The event log can always be rebuilt by replaying the input journal, so it is flushed second.
 */
public final class JournalHandler implements PipelineHandler, AutoCloseable {

    /** File-name prefixes of the two journals in the journal directory. */
    public static final String INPUT = "input";

    public static final String EVENTS = "events";

    /** Seq of the {@link EngineSetup} record; commands start at 1. */
    public static final long SETUP_SEQ = 0;

    private final Journal input;
    private final Journal events;
    private final JournalCodec codec = new JournalCodec();
    private final ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(1024);

    // The last event seq known to be on disk. Written by the journal thread after each flush, read by followers of
    // the event log (the Kafka publisher), which must never act on an event a crash could still erase.
    private volatile long durableEventSeq;

    /**
     * Starts a new recorded session: writes {@code setup} as record 0 of the input journal and flushes it.
     *
     * @throws IllegalStateException if the journals already hold a session; use {@link #resume} after
     *     {@link JournalRecovery} instead
     */
    public JournalHandler(Journal input, Journal events, EngineSetup setup) throws IOException {
        if (input.lastSeq() != -1 || events.lastSeq() != -1) {
            throw new IllegalStateException("journal already holds a session; recover and resume it instead");
        }
        this.input = input;
        this.events = events;
        this.durableEventSeq = events.lastSeq();
        int length = codec.encode(setup, buffer, 0);
        input.append(SETUP_SEQ, buffer, 0, length);
        input.flush();
    }

    private JournalHandler(Journal input, Journal events) {
        this.input = input;
        this.events = events;
        this.durableEventSeq = events.lastSeq();
    }

    /**
     * Continues a recovered session: appends after what is already there. Both journals must end exactly where
     * recovery left them, or the files changed underneath us.
     */
    public static JournalHandler resume(Journal input, Journal events, JournalRecovery.Recovered recovered) {
        if (input.lastSeq() != recovered.lastInputSeq() || events.lastSeq() != recovered.lastEventSeq()) {
            throw new IllegalStateException("journals moved since recovery: input " + input.lastSeq() + " vs "
                    + recovered.lastInputSeq() + ", events " + events.lastSeq() + " vs "
                    + recovered.lastEventSeq());
        }
        return new JournalHandler(input, events);
    }

    /** Opens (or creates) both journals in {@code dir} and starts a session there. */
    public static JournalHandler create(Path dir, EngineSetup setup) throws IOException {
        FileJournal input = FileJournal.open(dir, INPUT);
        FileJournal events = FileJournal.open(dir, EVENTS);
        try {
            return new JournalHandler(input, events, setup);
        } catch (IOException | RuntimeException e) {
            input.close();
            events.close();
            throw e;
        }
    }

    @Override
    public void onSlot(CommandSlot slot, boolean endOfBatch) throws IOException {
        int length = codec.encode(slot.command(), buffer, 0);
        input.append(slot.inputSeq(), buffer, 0, length);
        for (ExchangeEvent event : slot.events()) {
            length = codec.encode(event, buffer, 0);
            events.append(event.seq(), buffer, 0, length);
        }
        if (endOfBatch) {
            input.flush();
            events.flush();
            durableEventSeq = events.lastSeq();
        }
    }

    /** The highest event seq that is flushed to disk (-1 if none). Safe to call from any thread. */
    public long durableEventSeq() {
        return durableEventSeq;
    }

    /** Flushes and closes both journals. Call after the pipeline has stopped. */
    @Override
    public void close() throws IOException {
        try {
            input.close();
        } finally {
            events.close();
        }
    }
}
