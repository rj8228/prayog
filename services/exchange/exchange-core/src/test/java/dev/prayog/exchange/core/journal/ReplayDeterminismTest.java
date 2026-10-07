package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The S8 acceptance test, run in CI by {@code make replay-check}: record a busy session through the real pipeline,
 * replay its input journal into a fresh engine, and get the same event-log checksum.
 */
class ReplayDeterminismTest {

    @TempDir
    Path dir;

    @Test
    void replayingARecordedSessionGivesTheSameEventLogChecksum() throws Exception {
        record(dir, SessionWorkload.SETUP, SessionWorkload.SETUP, 4, 12_500);

        Replay.Report report = Replay.check(dir);

        System.out.printf(
                "replay-check: %d commands, %d events, sha256 %s%n",
                report.commands(),
                report.recorded().records(),
                report.recorded().sha256());
        assertThat(report.commands()).isGreaterThan(50_000);
        assertThat(eventCounts(dir))
                .as("the session exercised the engine, not just its reject path")
                .containsKeys(
                        "Trade", "OrderAccepted", "OrderModified", "OrderRejected", "OrderCancelled", "OrderModified")
                .containsEntry("expired", true)
                .containsEntry("halted", true)
                .containsEntry("closed", true);
        assertThat(report.firstDifference()).isNull();
        assertThat(report.replayed()).isEqualTo(report.recorded());
        assertThat(report.matches()).isTrue();
    }

    @Test
    void archivingSegmentsDoesNotChangeTheChecksum() throws Exception {
        record(dir, SessionWorkload.SETUP, SessionWorkload.SETUP, 2, 2_000, 64 * 1024);
        Replay.Report before = Replay.check(dir);

        JournalArchiver.Result input = JournalArchiver.archive(dir, JournalHandler.INPUT, Long.MAX_VALUE);
        JournalArchiver.Result events = JournalArchiver.archive(dir, JournalHandler.EVENTS, Long.MAX_VALUE);
        Replay.Report after = Replay.check(dir);

        assertThat(input.segments()).as("input segments archived").isPositive();
        assertThat(events.segments()).as("event segments archived").isPositive();
        assertThat(after.matches()).isTrue();
        assertThat(after.recorded()).isEqualTo(before.recorded());
        assertThat(after.replayed()).isEqualTo(before.replayed());
    }

    /**
     * Config drift: the live engine ran with wider bands than the setup it journaled. The replay produces different
     * events, and the check must say so and point at the first one.
     */
    @Test
    void aSessionThatCannotBeReproducedIsCaught() throws Exception {
        List<Instrument> wider = SessionWorkload.INSTRUMENTS.stream()
                .map(i -> new Instrument(i.symbol(), i.tickSize(), i.maxOrderQuantity(), i.referencePrice(), 20))
                .toList();
        record(dir, SessionWorkload.SETUP, new EngineSetup(wider, SessionWorkload.SCHEDULE), 2, 2_000);

        Replay.Report report = Replay.check(dir);

        assertThat(report.matches()).isFalse();
        assertThat(report.firstDifference()).startsWith("event #");
    }

    @Test
    void theOnlineCheckComparesWhatBothLogsHaveReached() throws Exception {
        record(dir, SessionWorkload.SETUP, SessionWorkload.SETUP, 2, 2_000);
        // Simulate a live exchange whose event log is behind: drop the last part of the event log.
        java.nio.file.Path events = Segments.list(dir, JournalHandler.EVENTS).getLast();
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(events.toFile(), "rw")) {
            file.setLength(file.length() * 3 / 4);
        }

        Replay.OnlineReport report = Replay.checkOnline(dir);

        assertThat(report.matches()).isTrue();
        assertThat(report.comparedUpTo()).isPositive();
        assertThat(report.recorded().records()).isEqualTo(report.comparedUpTo());
    }

    @Test
    void theOnlineCheckCatchesADifference() throws Exception {
        List<Instrument> wider = SessionWorkload.INSTRUMENTS.stream()
                .map(i -> new Instrument(i.symbol(), i.tickSize(), i.maxOrderQuantity(), i.referencePrice(), 20))
                .toList();
        record(dir, SessionWorkload.SETUP, new EngineSetup(wider, SessionWorkload.SCHEDULE), 2, 2_000);

        assertThat(Replay.checkOnline(dir).matches()).isFalse();
    }

    @Test
    void aGapInTheInputJournalStopsTheReplay() throws IOException {
        JournalCodec codec = new JournalCodec();
        UnsafeBuffer buffer = new UnsafeBuffer(new byte[4096]);
        try (FileJournal input = FileJournal.open(dir, JournalHandler.INPUT)) {
            input.append(0, buffer, 0, codec.encode(SessionWorkload.SETUP, buffer, 0));
            input.append(1, buffer, 0, codec.encode(new ClockTick(1), buffer, 0));
            input.append(3, buffer, 0, codec.encode(new ClockTick(2), buffer, 0));
        }

        assertThatThrownBy(() -> Replay.replayed(dir)).hasMessageContaining("expected seq 2, found 3");
    }

    // Counts events by type, and flags whether expiry, halts and closes happened.
    private static Map<String, Object> eventCounts(Path dir) throws IOException {
        JournalCodec codec = new JournalCodec();
        Map<String, Object> counts = new TreeMap<>();
        JournalReader.read(dir, JournalHandler.EVENTS, (seq, buffer, offset, length) -> {
            ExchangeEvent event = codec.decodeEvent(buffer, offset);
            counts.merge(event.getClass().getSimpleName(), 1L, (a, b) -> (Long) a + (Long) b);
            if (event instanceof OrderRejected r) {
                counts.merge("rejected " + r.reason(), 1L, (a, b) -> (Long) a + (Long) b);
            }
            if (event instanceof OrderCancelled c && c.reason() == CancelReason.EXPIRED) {
                counts.put("expired", true);
            }
            if (event instanceof SessionStateChanged s) {
                counts.put(
                        s.state() == SessionState.HALTED
                                ? "halted"
                                : s.state().name().toLowerCase(),
                        true);
            }
        });
        System.out.println("replay-check: event mix " + counts);
        return counts;
    }

    /**
     * Records a session. {@code journaled} is what the journal says the engine was; {@code live} is what actually ran
     * (they differ only in the drift test).
     */
    private static void record(Path dir, EngineSetup journaled, EngineSetup live, int threads, int perThread)
            throws Exception {
        record(dir, journaled, live, threads, perThread, FileJournal.DEFAULT_SEGMENT_SIZE);
    }

    private static void record(
            Path dir, EngineSetup journaled, EngineSetup live, int threads, int perThread, long segmentSize)
            throws Exception {
        SessionWorkload.Acks acks = new SessionWorkload.Acks();
        try (JournalHandler journal = JournalHandler.create(dir, journaled, segmentSize)) {
            try (ExchangePipeline pipeline = ExchangePipeline.builder(
                            new PipelineConfig(4_096, WaitStrategyType.BLOCKING), live::newEngine)
                    .then(journal)
                    .then(acks)
                    .start()) {
                SessionWorkload.run(pipeline, acks, threads, perThread);
            }
        }
    }
}
