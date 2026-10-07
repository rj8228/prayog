package dev.prayog.exchange.app.snapshot;

import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.core.EngineState;
import dev.prayog.exchange.core.journal.JournalArchiver;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.Snapshot;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves snapshots (ADR 0016) on its own thread. The outbound stage hands over the state captured at a
 * {@code TakeSnapshot} command; encoding and the fsync happen here, so neither the pipeline nor clients wait for the
 * disk. Market statistics travel inside the snapshot as JSON (the "app" bytes).
 *
 * <p>After each snapshot it also archives old journal segments (ADR 0022), on the same thread and so also off the
 * order path. The safe point is the oldest kept snapshot: recovery from any kept snapshot then reads only live
 * segments. The event log also waits for the Kafka publisher, which reads it from its checkpoint.
 */
public final class SnapshotWriter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SnapshotWriter.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Path dir;
    private final int keep;
    private final ExecutorService thread = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "prayog-snapshots");
        t.setDaemon(true);
        return t;
    });
    private final AtomicLong lastInputSeq = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final LongSupplier publishedEventSeq;

    /**
     * @param publishedEventSeq the highest event seq a reader of the event log is done with (the Kafka publisher's
     *     checkpoint; {@code Long.MAX_VALUE} when nothing publishes)
     */
    public SnapshotWriter(Path dir, int keep, LongSupplier publishedEventSeq) {
        this.dir = dir;
        this.keep = keep;
        this.publishedEventSeq = publishedEventSeq;
    }

    /** What one archiving run did, and the seqs it archived up to (0 when no snapshot exists yet). */
    public record ArchiveReport(
            int segments, long originalBytes, long archivedBytes, long inputUpToSeq, long eventUpToSeq) {}

    /** Queues one snapshot for writing. Everything passed in is an immutable copy. */
    public void submit(
            long inputSeq,
            long eventSeq,
            EngineState engine,
            OrderTracker.State tracker,
            MarketHub.MarketSnapshot market) {
        thread.execute(() -> {
            long start = System.nanoTime();
            try {
                Snapshot snapshot = new Snapshot(inputSeq, eventSeq, engine, tracker, JSON.writeValueAsBytes(market));
                Path file = snapshot.write(dir, keep);
                lastInputSeq.set(inputSeq);
                log.info(
                        "snapshot at input seq {} ({} resting orders) written to {} in {} ms",
                        inputSeq,
                        engine.orders().size(),
                        file.getFileName(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            } catch (IOException | RuntimeException e) {
                errors.incrementAndGet();
                log.warn("snapshot at input seq {} could not be written; recovery will use an older one", inputSeq, e);
                return;
            }
            try {
                archiveOnThisThread();
            } catch (IOException | RuntimeException e) {
                errors.incrementAndGet();
                log.warn("journal archiving failed; segments stay live and readable", e);
            }
        });
    }

    /** Archives now, on the snapshot thread (after any snapshot already queued). */
    public CompletableFuture<ArchiveReport> archive() {
        CompletableFuture<ArchiveReport> done = new CompletableFuture<>();
        thread.execute(() -> {
            try {
                done.complete(archiveOnThisThread());
            } catch (IOException | RuntimeException e) {
                errors.incrementAndGet();
                done.completeExceptionally(e);
            }
        });
        return done;
    }

    /** Bytes the journals use on disk, live and archived. */
    public JournalArchiver.Usage usage() {
        try {
            JournalArchiver.Usage input = JournalArchiver.usage(dir, JournalHandler.INPUT);
            JournalArchiver.Usage events = JournalArchiver.usage(dir, JournalHandler.EVENTS);
            return new JournalArchiver.Usage(
                    input.liveBytes() + events.liveBytes(), input.archivedBytes() + events.archivedBytes());
        } catch (IOException e) {
            return new JournalArchiver.Usage(-1, -1); // a file vanished mid-listing; the next call will see it
        }
    }

    private ArchiveReport archiveOnThisThread() throws IOException {
        List<Path> kept = Snapshot.list(dir);
        if (kept.isEmpty()) {
            return new ArchiveReport(0, 0, 0, 0, 0);
        }
        Snapshot oldest = Snapshot.decode(Files.readAllBytes(kept.getFirst()));
        long eventUpTo = Math.min(oldest.eventSeq(), publishedEventSeq.getAsLong());
        JournalArchiver.Result input = JournalArchiver.archive(dir, JournalHandler.INPUT, oldest.inputSeq());
        JournalArchiver.Result events = JournalArchiver.archive(dir, JournalHandler.EVENTS, eventUpTo);
        ArchiveReport report = new ArchiveReport(
                input.segments() + events.segments(),
                input.originalBytes() + events.originalBytes(),
                input.archivedBytes() + events.archivedBytes(),
                oldest.inputSeq(),
                eventUpTo);
        if (report.segments() > 0) {
            log.info(
                    "archived {} journal segments: {} MiB to {} MiB (input up to seq {}, events up to seq {})",
                    report.segments(),
                    report.originalBytes() >> 20,
                    report.archivedBytes() >> 20,
                    report.inputUpToSeq(),
                    report.eventUpToSeq());
        }
        return report;
    }

    /** The market statistics stored in a snapshot. */
    public static MarketHub.MarketSnapshot market(Snapshot snapshot) {
        return JSON.readValue(snapshot.app(), MarketHub.MarketSnapshot.class);
    }

    /** Input seq of the last snapshot written by this process (0 if none). */
    public long lastInputSeq() {
        return lastInputSeq.get();
    }

    public long errors() {
        return errors.get();
    }

    /** Finishes the snapshots already queued. */
    @Override
    public void close() throws InterruptedException {
        thread.shutdown();
        if (!thread.awaitTermination(30, TimeUnit.SECONDS)) {
            log.warn("snapshot writer still busy after 30 s; stopping it");
            thread.shutdownNow();
        }
    }
}
