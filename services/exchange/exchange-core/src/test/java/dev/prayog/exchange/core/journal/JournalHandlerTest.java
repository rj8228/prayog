package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.agrona.DirectBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalHandlerTest {

    @TempDir
    Path dir;

    /** Wraps a journal and remembers the highest seq that has been flushed. */
    private static final class Durable implements Journal {
        private final Journal delegate;
        final AtomicLong durableSeq = new AtomicLong(-1);
        final AtomicLong flushes = new AtomicLong();

        Durable(Journal delegate) {
            this.delegate = delegate;
        }

        @Override
        public void append(long seq, DirectBuffer payload, int offset, int length) throws IOException {
            delegate.append(seq, payload, offset, length);
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            flushes.incrementAndGet();
            durableSeq.set(delegate.lastSeq());
        }

        @Override
        public long lastSeq() {
            return delegate.lastSeq();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private record Seen(long inputSeq, Command command, List<ExchangeEvent> events, boolean durable) {}

    @Test
    void journalsEveryCommandAndEventBeforeLaterStagesSeeThem() throws Exception {
        Durable input = new Durable(FileJournal.open(dir, JournalHandler.INPUT, 64 * 1024));
        Durable events = new Durable(FileJournal.open(dir, JournalHandler.EVENTS, 64 * 1024));
        JournalHandler journal = new JournalHandler(input, events, SessionWorkload.SETUP);
        List<Seen> seen = new ArrayList<>();
        SessionWorkload.Acks acks = new SessionWorkload.Acks();
        PipelineHandler responses = (CommandSlot slot, boolean endOfBatch) -> {
            long lastEvent =
                    slot.events().isEmpty() ? -1 : slot.events().getLast().seq();
            boolean durable = slot.inputSeq() <= input.durableSeq.get()
                    && lastEvent <= events.durableSeq.get()
                    // what followers of the event log are told is durable: covers this slot, never more than disk
                    && lastEvent <= journal.durableEventSeq()
                    && journal.durableEventSeq() <= events.durableSeq.get();
            seen.add(new Seen(slot.inputSeq(), slot.command(), List.copyOf(slot.events()), durable));
        };

        try (ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(256, WaitStrategyType.BLOCKING), SessionWorkload.SETUP::newEngine)
                .then(journal)
                .then(responses, acks)
                .start()) {
            SessionWorkload.run(pipeline, acks, 2, 1_000);
        }
        journal.close();

        assertThat(seen).hasSizeGreaterThan(2_000);
        assertThat(seen)
                .allSatisfy(s -> assertThat(s.durable())
                        .as("seq %d reached stage 2 before it was flushed", s.inputSeq())
                        .isTrue());
        assertThat(input.flushes.get())
                .as("one flush per batch, not per command")
                .isLessThan(seen.size());

        JournalCodec codec = new JournalCodec();
        List<Object> inputRecords = new ArrayList<>();
        JournalReader.read(
                dir,
                JournalHandler.INPUT,
                (seq, buffer, offset, length) -> inputRecords.add(
                        seq == JournalHandler.SETUP_SEQ
                                ? codec.decodeEngineSetup(buffer, offset)
                                : codec.decodeCommand(buffer, offset)));
        List<Object> expectedInput = new ArrayList<>();
        expectedInput.add(SessionWorkload.SETUP);
        seen.forEach(s -> expectedInput.add(s.command()));
        assertThat(inputRecords).containsExactlyElementsOf(expectedInput);

        List<ExchangeEvent> eventRecords = new ArrayList<>();
        JournalReader.read(
                dir,
                JournalHandler.EVENTS,
                (seq, buffer, offset, length) -> eventRecords.add(codec.decodeEvent(buffer, offset)));
        assertThat(eventRecords)
                .containsExactlyElementsOf(
                        seen.stream().flatMap(s -> s.events().stream()).toList());
    }

    @Test
    void refusesToStartOverAnExistingSession() throws IOException {
        JournalHandler.create(dir, SessionWorkload.SETUP).close();

        assertThatThrownBy(() -> JournalHandler.create(dir, SessionWorkload.SETUP))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already holds a session");
    }
}
