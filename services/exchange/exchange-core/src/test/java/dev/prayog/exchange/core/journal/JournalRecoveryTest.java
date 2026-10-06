package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournalRecoveryTest {

    @TempDir
    Path dir;

    /**
     * Restart in the middle of a session: record, stop, recover, continue, stop. The two halves must replay as one
     * unbroken session (same checksum, contiguous sequence numbers), which is only true if recovery rebuilt exactly
     * the state the first half left behind.
     */
    @Test
    void aRestartedSessionReplaysAsOneUnbrokenSession() throws Exception {
        runFresh(2, 1_500);
        long firstHalf = lastInputSeq();

        List<ExchangeEvent> replayed = new ArrayList<>();
        resumeAndRun(replayed::add, 2, 1_500);

        Replay.Report report = Replay.check(dir);
        assertThat(report.matches())
                .as(String.valueOf(report.firstDifference()))
                .isTrue();
        assertThat(report.commands()).isGreaterThan(firstHalf);
        assertThat(replayed).as("recovery hands every replayed event on").isNotEmpty();
        assertThat(replayed.getLast().seq()).isEqualTo(replayed.size());
    }

    @Test
    void repairsAnEventLogThatLostItsTail() throws Exception {
        runFresh(2, 1_000);
        Path events = Segments.list(dir, JournalHandler.EVENTS).getLast();
        try (RandomAccessFile file = new RandomAccessFile(events.toFile(), "rw")) {
            file.setLength(file.length() / 2); // as if the machine died between the input flush and the events flush
        }

        long repaired;
        try (FileJournal input = FileJournal.open(dir, JournalHandler.INPUT);
                FileJournal eventLog = FileJournal.open(dir, JournalHandler.EVENTS)) {
            JournalRecovery.Recovered recovered = JournalRecovery.recover(dir, eventLog, e -> {});
            repaired = recovered.repairedEvents();
            assertThat(eventLog.lastSeq()).isEqualTo(recovered.lastEventSeq());
        }

        assertThat(repaired).isPositive();
        assertThat(Replay.check(dir).matches()).isTrue();
    }

    @Test
    void anEventLogAheadOfTheInputJournalIsRefused() throws Exception {
        runFresh(1, 200);
        // Drop the second half of the input journal (cut exactly after a record) but keep every event: the events
        // can no longer be explained by the commands.
        Path input = Segments.list(dir, JournalHandler.INPUT).getLast();
        List<Long> recordEnds = new ArrayList<>();
        JournalReader.read(
                dir,
                JournalHandler.INPUT,
                (seq, buffer, offset, length) -> recordEnds.add((long) offset + length + 4)); // payload end + CRC
        assertThat(recordEnds).as("records in the input journal").hasSizeGreaterThan(100);
        long cut = recordEnds.get(recordEnds.size() / 2);
        long before = Files.size(input);
        try (RandomAccessFile file = new RandomAccessFile(input.toFile(), "rw")) {
            file.setLength(cut);
        }
        try (FileJournal inputJournal = FileJournal.open(dir, JournalHandler.INPUT);
                FileJournal eventLog = FileJournal.open(dir, JournalHandler.EVENTS)) {
            assertThat(inputJournal.lastSeq())
                    .as("input journal after the cut (%d of %d bytes)", cut, before)
                    .isEqualTo(recordEnds.size() / 2);
            assertThatThrownBy(() -> JournalRecovery.recover(dir, eventLog, e -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("event log ends at seq");
        }
    }

    @Test
    void anEmptyJournalHasNothingToRecover() throws IOException {
        try (FileJournal eventLog = FileJournal.open(dir, JournalHandler.EVENTS)) {
            assertThatThrownBy(() -> JournalRecovery.recover(dir, eventLog, e -> {}))
                    .hasMessageContaining("nothing to recover");
        }
        assertThat(Files.exists(dir)).isTrue();
    }

    @Test
    void resumeRefusesJournalsThatMovedSinceRecovery() throws Exception {
        runFresh(1, 100);
        try (FileJournal input = FileJournal.open(dir, JournalHandler.INPUT);
                FileJournal eventLog = FileJournal.open(dir, JournalHandler.EVENTS)) {
            JournalRecovery.Recovered recovered = JournalRecovery.recover(dir, eventLog, e -> {});
            input.append(recovered.lastInputSeq() + 1, new org.agrona.concurrent.UnsafeBuffer(new byte[1]), 0, 1);

            assertThatThrownBy(() -> JournalHandler.resume(input, eventLog, recovered))
                    .hasMessageContaining("journals moved");
        }
    }

    /**
     * The event log says something the input journal does not produce (here: one order's quantity doctored; in real
     * life: a rule changed without SetRules). Recovery must refuse to start, not trade on a different book.
     */
    @Test
    void anEventLogThatReplayDoesNotReproduceIsRefused() throws Exception {
        runFresh(2, 1_000);
        JournalCodec codec = new JournalCodec();
        List<ExchangeEvent> original = new ArrayList<>();
        JournalReader.read(dir, JournalHandler.EVENTS, (seq, b, o, l) -> original.add(codec.decodeEvent(b, o)));
        int doctored = 0;
        while (!(original.get(doctored) instanceof dev.prayog.contracts.event.OrderAccepted)) {
            doctored++;
        }
        for (Path segment : Segments.list(dir, JournalHandler.EVENTS)) {
            java.nio.file.Files.delete(segment);
        }
        org.agrona.ExpandableDirectByteBuffer buffer = new org.agrona.ExpandableDirectByteBuffer(256);
        try (FileJournal events = FileJournal.open(dir, JournalHandler.EVENTS)) {
            for (int i = 0; i < original.size(); i++) {
                ExchangeEvent event = original.get(i);
                if (i == doctored && event instanceof dev.prayog.contracts.event.OrderAccepted a) {
                    event = new dev.prayog.contracts.event.OrderAccepted(
                            a.seq(),
                            a.simTime(),
                            a.orderId(),
                            a.clientOrderId(),
                            a.accountId(),
                            a.symbol(),
                            a.side(),
                            a.orderType(),
                            a.price(),
                            a.quantity() + 1);
                }
                int length = codec.encode(event, buffer, 0);
                events.append(event.seq(), buffer, 0, length);
            }
        }
        long doctoredSeq = original.get(doctored).seq();

        try (FileJournal eventLog = FileJournal.open(dir, JournalHandler.EVENTS)) {
            assertThatThrownBy(() -> JournalRecovery.recover(dir, eventLog, e -> {}))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("does not reproduce the event log")
                    .hasMessageContaining("event " + doctoredSeq + " differs");
        }
    }

    private void runFresh(int threads, int perThread) throws Exception {
        SessionWorkload.Acks acks = new SessionWorkload.Acks();
        try (JournalHandler journal = JournalHandler.create(dir, SessionWorkload.SETUP);
                ExchangePipeline pipeline = ExchangePipeline.builder(
                                new PipelineConfig(1_024, WaitStrategyType.BLOCKING), SessionWorkload.SETUP::newEngine)
                        .then(journal)
                        .then(acks)
                        .start()) {
            SessionWorkload.run(pipeline, acks, threads, perThread);
        }
    }

    private void resumeAndRun(java.util.function.Consumer<ExchangeEvent> replayed, int threads, int perThread)
            throws Exception {
        SessionWorkload.Acks acks = new SessionWorkload.Acks();
        FileJournal input = FileJournal.open(dir, JournalHandler.INPUT);
        FileJournal events = FileJournal.open(dir, JournalHandler.EVENTS);
        JournalRecovery.Recovered recovered = JournalRecovery.recover(dir, events, replayed);
        try (JournalHandler journal = JournalHandler.resume(input, events, recovered);
                ExchangePipeline pipeline = ExchangePipeline.builder(
                                new PipelineConfig(1_024, WaitStrategyType.BLOCKING), recovered.engineFactory())
                        .continueAfter(recovered.lastInputSeq())
                        .then(journal)
                        .then(acks)
                        .start()) {
            SessionWorkload.run(pipeline, acks, threads, perThread);
        }
    }

    private long lastInputSeq() throws IOException {
        try (FileJournal input = FileJournal.open(dir, JournalHandler.INPUT)) {
            return input.lastSeq();
        }
    }
}
