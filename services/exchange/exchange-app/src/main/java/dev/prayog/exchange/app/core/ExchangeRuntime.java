package dev.prayog.exchange.app.core;

import dev.prayog.contracts.SessionState;
import dev.prayog.exchange.app.account.AccountHub;
import dev.prayog.exchange.app.config.ExchangeProperties;
import dev.prayog.exchange.app.kafka.KafkaEventPublisher;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.app.snapshot.SnapshotWriter;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.EventSink;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.TakeSnapshot;
import dev.prayog.exchange.core.journal.EngineSetup;
import dev.prayog.exchange.core.journal.FileJournal;
import dev.prayog.exchange.core.journal.JournalArchiver;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.JournalRecovery;
import dev.prayog.exchange.core.journal.Snapshot;
import dev.prayog.exchange.core.pipeline.ClockTicker;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.SimClock;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running exchange: journal, pipeline (matching → journal → outbound), sim clock and ticker.
 *
 * <p>Start-up: if the journal directory already holds a session, the engine and market data are rebuilt from it
 * ({@link JournalRecovery}) and trading continues where it stopped; otherwise a new session starts with the configured
 * instruments and schedule.
 */
public final class ExchangeRuntime implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ExchangeRuntime.class);
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private final ExchangePipeline pipeline;
    private final JournalHandler journal;
    private final OutboundHandler outbound;
    private final SimClock clock;
    private final ClockTicker ticker;
    private final SessionSchedule schedule;
    private final ScheduledExecutorService housekeeping;
    private final MarketHub market;
    private final KafkaEventPublisher kafka; // null when publishing is off
    private final SnapshotWriter snapshots;
    private final long recoveredFromSnapshot; // input seq of the snapshot recovery started from, 0 if none
    private final boolean recovered;
    private final long startedAfterSeq;
    private volatile long closedSinceWallNanos = -1;

    private ExchangeRuntime(ExchangeProperties props, MarketHub market, AccountHub accounts) throws IOException {
        this.market = market;
        FileJournal input = FileJournal.open(props.journalDir(), JournalHandler.INPUT);
        FileJournal events = FileJournal.open(props.journalDir(), JournalHandler.EVENTS);
        long startSim;
        Function<EventSink, MatchingEngine> engineFactory;
        long recoveredFrom = 0;
        if (input.lastSeq() < 0) {
            EngineSetup setup =
                    new EngineSetup(props.toInstruments(), props.schedule().toSchedule());
            journal = new JournalHandler(input, events, setup);
            market.setInstruments(setup.instruments());
            schedule = setup.schedule();
            engineFactory = setup::newEngine;
            startedAfterSeq = 0;
            startSim = freshStart(props);
            recovered = false;
            log.info(
                    "new session in {}: {} instruments",
                    props.journalDir(),
                    setup.instruments().size());
        } else {
            Snapshot start = usableSnapshot(props, input.lastSeq(), events.lastSeq(), market);
            JournalRecovery.Recovered r = JournalRecovery.recover(props.journalDir(), events, market::replay, start);
            market.finishReplay();
            market.setInstruments(r.setup().instruments());
            journal = JournalHandler.resume(input, events, r);
            schedule = r.setup().schedule();
            engineFactory = r.engineFactory();
            startedAfterSeq = r.lastInputSeq();
            startSim = r.lastSimTime() > 0 ? r.lastSimTime() : freshStart(props);
            recovered = true;
            recoveredFrom = r.snapshot() == null ? 0 : r.snapshot().inputSeq();
            log.info(
                    "recovered session from {}: {} commands, {} events, {} events repaired; {} commands replayed{}",
                    props.journalDir(),
                    r.lastInputSeq(),
                    r.lastEventSeq(),
                    r.repairedEvents(),
                    r.replayedCommands(),
                    r.snapshot() == null ? " (no snapshot)" : " after the snapshot at input seq " + recoveredFrom);
        }
        recoveredFromSnapshot = recoveredFrom;
        snapshots =
                new SnapshotWriter(props.journalDir(), snapshotSettings(props).keep(), this::publishedEventSeq);
        outbound = new OutboundHandler(market, accounts, snapshots, startedAfterSeq, events.lastSeq());
        pipeline = ExchangePipeline.builder(props.pipeline().toConfig(), engineFactory)
                .continueAfter(startedAfterSeq)
                .then(journal)
                .then(outbound)
                .start();
        // Bring the engine to the latest matching rules at a recorded point in the input (ADR 0014): history before
        // it replays under the rules it was made with. A no-op command when the engine is already there.
        try {
            submit(new SetRules(MatchingEngine.LATEST_RULES)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while setting the matching rules", e);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new IOException("could not set the matching rules", e);
        }
        clock = new SimClock(startSim, props.clock().multiplier(), System::nanoTime);
        ticker = new ClockTicker(clock, pipeline, ClockTicker.DEFAULT_INTERVAL_MILLIS);
        ticker.start();
        kafka = props.kafka() != null && props.kafka().enabled()
                ? new KafkaEventPublisher(
                                props.journalDir(),
                                journal::durableEventSeq,
                                KafkaEventPublisher.producers(props.kafka().bootstrap()),
                                KafkaEventPublisher.Settings.defaults(
                                        props.kafka().topic()))
                        .start()
                : null;
        housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "prayog-housekeeping");
            t.setDaemon(true);
            return t;
        });
        int every = snapshotSettings(props).everyMinutes();
        if (every > 0) {
            housekeeping.scheduleAtFixedRate(this::takeSnapshot, every, every, TimeUnit.MINUTES);
        }
        if (props.clock().autoNextDay()) {
            long pauseNanos = TimeUnit.SECONDS.toNanos(props.clock().closedPauseSeconds());
            housekeeping.scheduleAtFixedRate(() -> rollToNextDay(pauseNanos), 1, 1, TimeUnit.SECONDS);
        }
    }

    /** Opens the journal (recovering if needed) and starts trading. */
    public static ExchangeRuntime start(ExchangeProperties props, MarketHub market, AccountHub accounts)
            throws IOException {
        return new ExchangeRuntime(props, market, accounts);
    }

    /**
     * Submits a command and completes once it is journaled, with the events it produced. Fails fast with
     * {@link ExchangeBusyException} when the ring is full.
     */
    public CompletableFuture<CommandResult> submit(Command command) {
        CompletableFuture<CommandResult> result = new CompletableFuture<>();
        if (!pipeline.trySubmit(command, result)) {
            result.completeExceptionally(new ExchangeBusyException());
        }
        return result;
    }

    /**
     * Journals a {@link TakeSnapshot}; the snapshot is written in the background once the command has passed the
     * pipeline (ADR 0016). Completes when the state has been captured.
     */
    public CompletableFuture<CommandResult> takeSnapshot() {
        CompletableFuture<CommandResult> done = submit(new TakeSnapshot());
        done.exceptionally(e -> {
            log.warn("snapshot request refused", e);
            return null;
        });
        return done;
    }

    /** Archives old journal segments now (ADR 0022); normally this follows every snapshot. */
    public CompletableFuture<SnapshotWriter.ArchiveReport> archiveJournal() {
        return snapshots.archive();
    }

    // The event log's Kafka reader is done up to its checkpoint; with no publisher nothing else holds it back.
    private long publishedEventSeq() {
        KafkaEventPublisher publisher = kafka;
        return publisher != null ? publisher.status().publishedSeq() : Long.MAX_VALUE;
    }

    /** Sim time now, epoch microseconds. */
    public long simTime() {
        return clock.now();
    }

    public int clockMultiplier() {
        return clock.multiplier();
    }

    public void setClockMultiplier(int multiplier) {
        clock.setMultiplier(multiplier);
        log.info("clock multiplier set to {}", multiplier);
    }

    /** Jumps the clock to the next scheduled open (the engine closes the old day and opens the new one). */
    public void skipToNextOpen() {
        long next = nextOpen(clock.now());
        clock.advanceTo(next);
        ticker.tick();
        log.info("clock moved to next open {}", Instant.ofEpochSecond(next / MICROS_PER_SECOND));
    }

    public Status status() {
        JournalArchiver.Usage usage = snapshots.usage();
        return new Status(
                recovered,
                startedAfterSeq,
                outbound.lastInputSeq(),
                pipeline.capacity(),
                pipeline.remainingCapacity(),
                outbound.publishErrors(),
                clock.now(),
                clock.multiplier(),
                market.session(),
                kafka != null ? kafka.status() : new KafkaEventPublisher.Status(false, false, 0, 0, 0),
                recoveredFromSnapshot,
                snapshots.lastInputSeq(),
                usage.liveBytes(),
                usage.archivedBytes());
    }

    /** Operational state for the ops page and debugging. */
    public record Status(
            boolean recoveredFromJournal,
            long startedAfterInputSeq,
            long lastProcessedInputSeq,
            int ringCapacity,
            long ringRemaining,
            long publishErrors,
            long simTime,
            int clockMultiplier,
            SessionState session,
            KafkaEventPublisher.Status kafka,
            long recoveredFromSnapshotInputSeq,
            long lastSnapshotInputSeq,
            long journalLiveBytes,
            long journalArchivedBytes) {}

    @Override
    public void close() throws Exception {
        long start = System.nanoTime();
        housekeeping.shutdownNow();
        snapshots.stopArchiving(); // the shutdown snapshot must not queue behind a 64 MiB gzip
        ticker.close(); // no more clock ticks: the snapshot below is the last state
        try {
            // First, while everything is running: it takes milliseconds, and it is what makes the next start fast.
            takeSnapshot().get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("no snapshot at shutdown; the next start replays from an older one", e);
        }
        pipeline.close(); // drains everything already submitted through the journal
        snapshots.close(); // finishes writing the snapshot
        journal.close();
        if (kafka != null) {
            kafka.close(); // last, and bounded: anything not acknowledged is resent from the checkpoint on restart
        }
        log.info(
                "exchange stopped at input seq {} in {} ms",
                outbound.lastInputSeq(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
    }

    // With autoNextDay: once sim time has been outside trading hours for the pause, jump to the next open.
    private void rollToNextDay(long pauseNanos) {
        try {
            long now = clock.now();
            LocalTime local = toLocal(now).toLocalTime();
            boolean outsideHours = local.isBefore(schedule.open()) || !local.isBefore(schedule.close());
            if (!outsideHours) {
                closedSinceWallNanos = -1;
                return;
            }
            long wall = System.nanoTime();
            if (closedSinceWallNanos < 0) {
                closedSinceWallNanos = wall;
            } else if (wall - closedSinceWallNanos >= pauseNanos) {
                closedSinceWallNanos = -1;
                skipToNextOpen();
            }
        } catch (RuntimeException e) {
            log.warn("next-day roll failed", e);
        }
    }

    private static ExchangeProperties.Snapshots snapshotSettings(ExchangeProperties props) {
        return props.snapshots() != null ? props.snapshots() : new ExchangeProperties.Snapshots(5, 3);
    }

    /**
     * The newest snapshot recovery can start from, with market data already restored from it; null for a full replay.
     * A snapshot beyond the event log (the log was rebuilt) or with unreadable market data is skipped.
     */
    private static Snapshot usableSnapshot(ExchangeProperties props, long lastInput, long lastEvent, MarketHub market)
            throws IOException {
        Snapshot snapshot = Snapshot.latest(props.journalDir(), lastInput).orElse(null);
        if (snapshot == null || snapshot.eventSeq() > lastEvent) {
            return null;
        }
        try {
            market.restore(snapshot.tracker(), SnapshotWriter.market(snapshot));
            return snapshot;
        } catch (RuntimeException e) {
            log.warn(
                    "snapshot at input seq {} has unreadable market data; replaying the whole journal",
                    snapshot.inputSeq(),
                    e);
            return null;
        }
    }

    private long nextOpen(long simNow) {
        LocalDateTime local = toLocal(simNow);
        LocalDate day = local.toLocalTime().isBefore(schedule.open())
                ? local.toLocalDate()
                : local.toLocalDate().plusDays(1);
        return LocalDateTime.of(day, schedule.open()).toEpochSecond(schedule.offset()) * MICROS_PER_SECOND;
    }

    private LocalDateTime toLocal(long simMicros) {
        return LocalDateTime.ofEpochSecond(Math.floorDiv(simMicros, MICROS_PER_SECOND), 0, schedule.offset());
    }

    private static long freshStart(ExchangeProperties props) {
        ZoneOffset offset = props.schedule().zoneOffset();
        LocalDate date = props.clock().startDate() != null
                ? props.clock().startDate()
                : LocalDate.now(offset); // the only wall-clock date read: it picks the session's first day
        return LocalDateTime.of(date, props.clock().startTime()).toEpochSecond(offset) * MICROS_PER_SECOND;
    }
}
