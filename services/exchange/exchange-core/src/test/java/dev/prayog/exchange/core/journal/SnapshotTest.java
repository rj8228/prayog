package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.exchange.core.EngineState;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.TakeSnapshot;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** ADR 0016: snapshots are taken at an exact input seq, survive a round trip, and recovery from one equals a replay. */
class SnapshotTest {

    @TempDir
    Path dir;

    @Test
    void recoveringFromASnapshotGivesTheSameStateAsReplayingEverything() throws Exception {
        runWithSnapshots(2, 1_500);
        List<Path> files = Snapshot.list(dir);
        assertThat(files).as("snapshots written mid-session").hasSize(2);

        Recovery full = recover(null);
        Snapshot latest = Snapshot.latest(dir, Long.MAX_VALUE).orElseThrow();
        Recovery fast = recover(latest);

        assertThat(fast.recovered.snapshot()).isEqualTo(latest);
        assertThat(fast.recovered.replayedCommands()).isLessThan(full.recovered.replayedCommands());
        assertThat(fast.recovered.lastInputSeq()).isEqualTo(full.recovered.lastInputSeq());
        assertThat(fast.recovered.lastEventSeq()).isEqualTo(full.recovered.lastEventSeq());
        assertThat(fast.recovered.lastSimTime()).isEqualTo(full.recovered.lastSimTime());
        assertThat(fast.engine).as("engine state").isEqualTo(full.engine);
        assertThat(fast.tracker).as("derived open orders and book").isEqualTo(full.tracker);
    }

    @Test
    void aSnapshotMatchesTheStateAReplayReachesAtItsSeq() throws Exception {
        runWithSnapshots(2, 1_000);
        Snapshot snapshot = Snapshot.latest(dir, Long.MAX_VALUE).orElseThrow();
        // Replay the input journal up to the snapshot's seq with a fresh engine: it must be in the same state.
        JournalCodec codec = new JournalCodec();
        MatchingEngine[] engine = new MatchingEngine[1];
        JournalReader.read(dir, JournalHandler.INPUT, (seq, bytes, offset, length) -> {
            if (seq == JournalHandler.SETUP_SEQ) {
                engine[0] = codec.decodeEngineSetup(bytes, offset).newEngine(e -> {});
            } else if (seq <= snapshot.inputSeq()) {
                engine[0].apply(codec.decodeCommand(bytes, offset));
            }
        });
        assertThat(engine[0].snapshot()).isEqualTo(snapshot.engine());
        // The replay tool checks every snapshot the same way.
        Replay.SnapshotReport report = Replay.checkSnapshots(dir);
        assertThat(report.matches()).isTrue();
        assertThat(report.verified()).isEqualTo(Snapshot.list(dir).size());
    }

    @Test
    void roundTripAndDamage() throws Exception {
        runWithSnapshots(1, 400);
        Snapshot snapshot = Snapshot.latest(dir, Long.MAX_VALUE).orElseThrow();
        assertThat(Snapshot.decode(snapshot.encode())).isEqualTo(snapshot);

        byte[] bytes = snapshot.encode();
        bytes[bytes.length / 2] ^= 1;
        assertThatThrownBy(() -> Snapshot.decode(bytes))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void aDamagedNewestSnapshotFallsBackToTheOlderOne() throws Exception {
        runWithSnapshots(1, 600);
        List<Path> files = Snapshot.list(dir);
        Snapshot older = Snapshot.decode(Files.readAllBytes(files.get(0)));
        try (RandomAccessFile file = new RandomAccessFile(files.get(1).toFile(), "rw")) {
            file.seek(file.length() / 2);
            file.write(0x7F);
        }
        assertThat(Snapshot.latest(dir, Long.MAX_VALUE)).contains(older);
        assertThat(Snapshot.latest(dir, older.inputSeq() - 1))
                .as("nothing at or before that seq")
                .isEmpty();
    }

    @Test
    void onlyTheNewestSnapshotsAreKept() throws Exception {
        runWithSnapshots(1, 300);
        Snapshot s = Snapshot.latest(dir, Long.MAX_VALUE).orElseThrow();
        for (int i = 1; i <= 4; i++) {
            new Snapshot(s.inputSeq() + i, s.eventSeq(), s.engine(), s.tracker(), new byte[] {(byte) i}).write(dir, 2);
        }
        assertThat(Snapshot.list(dir)).hasSize(2);
        assertThat(Snapshot.latest(dir, Long.MAX_VALUE).orElseThrow().inputSeq())
                .isEqualTo(s.inputSeq() + 4);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private record Recovery(JournalRecovery.Recovered recovered, EngineState engine, OrderTracker.State tracker) {}

    // Recovers like the exchange does: tracker from the snapshot (if any), then fed every replayed event.
    private Recovery recover(Snapshot snapshot) throws IOException {
        OrderTracker tracker = new OrderTracker();
        if (snapshot != null) {
            tracker.restore(snapshot.tracker());
        }
        try (FileJournal events = FileJournal.open(dir, JournalHandler.EVENTS)) {
            JournalRecovery.Recovered r = JournalRecovery.recover(dir, events, tracker::onEvent, snapshot);
            tracker.endCommand();
            MatchingEngine engine = r.engineFactory().apply(e -> {});
            return new Recovery(r, engine.snapshot(), tracker.state());
        }
    }

    /** Runs the workload in three parts with a TakeSnapshot between them, saving snapshots like the exchange does. */
    private void runWithSnapshots(int threads, int perPart) throws Exception {
        SessionWorkload.Acks acks = new SessionWorkload.Acks();
        OrderTracker tracker = new OrderTracker();
        AtomicReference<Exception> failure = new AtomicReference<>();
        long[] lastEvent = {0};
        PipelineHandler saver = (CommandSlot slot, boolean endOfBatch) -> {
            slot.events().forEach(tracker::onEvent);
            tracker.endCommand();
            if (!slot.events().isEmpty()) {
                lastEvent[0] = slot.events().getLast().seq();
            }
            if (slot.snapshot() != null) {
                try {
                    new Snapshot(slot.inputSeq(), lastEvent[0], slot.snapshot(), tracker.state(), new byte[] {1, 2})
                            .write(dir, 3);
                } catch (IOException e) {
                    failure.set(e);
                }
            }
        };
        try (JournalHandler journal = JournalHandler.create(dir, SessionWorkload.SETUP);
                ExchangePipeline pipeline = ExchangePipeline.builder(
                                new PipelineConfig(1_024, WaitStrategyType.BLOCKING), SessionWorkload.SETUP::newEngine)
                        .then(journal)
                        .then(saver, acks)
                        .start()) {
            SessionWorkload.run(pipeline, acks, threads, perPart);
            pipeline.submit(new TakeSnapshot());
            SessionWorkload.run(pipeline, acks, threads, perPart);
            pipeline.submit(new TakeSnapshot());
            SessionWorkload.run(pipeline, acks, threads, perPart);
        }
        if (failure.get() != null) {
            throw failure.get();
        }
    }
}
