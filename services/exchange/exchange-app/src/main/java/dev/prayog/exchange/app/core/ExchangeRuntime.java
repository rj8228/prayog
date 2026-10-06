package dev.prayog.exchange.app.core;

import dev.prayog.contracts.SessionState;
import dev.prayog.exchange.app.account.AccountHub;
import dev.prayog.exchange.app.config.ExchangeProperties;
import dev.prayog.exchange.app.kafka.KafkaEventPublisher;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.EventSink;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.journal.EngineSetup;
import dev.prayog.exchange.core.journal.FileJournal;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.JournalRecovery;
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
    private final boolean recovered;
    private final long startedAfterSeq;
    private volatile long closedSinceWallNanos = -1;

    private ExchangeRuntime(ExchangeProperties props, MarketHub market, AccountHub accounts) throws IOException {
        this.market = market;
        FileJournal input = FileJournal.open(props.journalDir(), JournalHandler.INPUT);
        FileJournal events = FileJournal.open(props.journalDir(), JournalHandler.EVENTS);
        long startSim;
        Function<EventSink, MatchingEngine> engineFactory;
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
            JournalRecovery.Recovered r = JournalRecovery.recover(props.journalDir(), events, market::replay);
            market.finishReplay();
            market.setInstruments(r.setup().instruments());
            journal = JournalHandler.resume(input, events, r);
            schedule = r.setup().schedule();
            engineFactory = r.engineFactory();
            startedAfterSeq = r.lastInputSeq();
            startSim = r.lastSimTime() > 0 ? r.lastSimTime() : freshStart(props);
            recovered = true;
            log.info(
                    "recovered session from {}: {} commands, {} events, {} events repaired",
                    props.journalDir(),
                    r.lastInputSeq(),
                    r.lastEventSeq(),
                    r.repairedEvents());
        }
        outbound = new OutboundHandler(market, accounts, startedAfterSeq);
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
                kafka != null ? kafka.status() : new KafkaEventPublisher.Status(false, false, 0, 0, 0));
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
            KafkaEventPublisher.Status kafka) {}

    @Override
    public void close() throws Exception {
        housekeeping.shutdownNow();
        if (kafka != null) {
            kafka.close(); // first: it only reads the journal, and saves its checkpoint on the way out
        }
        ticker.close();
        pipeline.close(); // drains everything already submitted through the journal
        journal.close();
        log.info("exchange stopped at input seq {}", outbound.lastInputSeq());
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
