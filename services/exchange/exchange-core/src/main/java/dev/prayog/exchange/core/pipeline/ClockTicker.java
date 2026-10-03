package dev.prayog.exchange.core.pipeline;

import dev.prayog.exchange.core.ClockTick;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * A thread off the order path that publishes {@code ClockTick(clock.now())} every interval (100 ms by default). The
 * multiplier changes only what each tick says, not how often ticks come, so the input journal grows at the same
 * rate whatever the speed.
 */
public final class ClockTicker implements AutoCloseable {

    public static final long DEFAULT_INTERVAL_MILLIS = 100;

    private final SimClock clock;
    private final ExchangePipeline pipeline;
    private final long intervalNanos;
    private volatile boolean running;
    private Thread thread;

    public ClockTicker(SimClock clock, ExchangePipeline pipeline, long intervalMillis) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.pipeline = Objects.requireNonNull(pipeline, "pipeline");
        if (intervalMillis <= 0) {
            throw new IllegalArgumentException("intervalMillis must be positive: " + intervalMillis);
        }
        this.intervalNanos = TimeUnit.MILLISECONDS.toNanos(intervalMillis);
    }

    /** Publishes one tick now. */
    public void tick() {
        pipeline.submit(new ClockTick(clock.now()));
    }

    public synchronized void start() {
        if (thread != null) {
            throw new IllegalStateException("already started");
        }
        running = true;
        thread = new Thread(this::run, "prayog-clock");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        while (running) {
            tick();
            LockSupport.parkNanos(intervalNanos);
        }
    }

    @Override
    public synchronized void close() throws InterruptedException {
        running = false;
        if (thread != null) {
            LockSupport.unpark(thread);
            thread.join();
        }
    }
}
