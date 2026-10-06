package dev.prayog.exchange.bench;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.SetSessionState;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * The matching engine alone, on one thread, no journal (S9). Each benchmark keeps the book in a steady state so the
 * numbers do not drift as the run goes on: what one operation costs on a book of {@code depth} levels per side.
 *
 * <pre>make bench</pre>
 */
@State(Scope.Thread)
@BenchmarkMode({Mode.AverageTime})
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(
        value = 1,
        jvmArgsAppend = {"--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED"})
public class MatchingEngineBenchmark {

    private static final long T0 = 1_790_000_000_000_000L;
    private static final long MID = 150_000;
    private static final long TICK = 5;
    private static final Instrument INFY = new Instrument("INFY", TICK, 1_000_000, MID, 20);

    /** Price levels per side, each with a few resting orders. */
    @Param({"10", "1000"})
    int depth;

    private MatchingEngine engine;
    private Blackhole sink;
    private long lastOrderId;
    private String[] ids;
    private int next;

    @Setup(Level.Iteration)
    public void setUp(Blackhole blackhole) {
        sink = blackhole;
        engine = new MatchingEngine(List.of(INFY), this::onEvent);
        engine.apply(new ClockTick(T0));
        engine.apply(new SetRules(MatchingEngine.LATEST_RULES));
        engine.apply(new SetSessionState(SessionState.OPEN));
        // Client order IDs, reused cyclically: more than the 10,000 each account remembers, so no duplicates.
        ids = new String[1 << 15];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "c" + i;
        }
        for (int level = 1; level <= depth; level++) {
            for (int k = 0; k < 4; k++) {
                place(2 + k, Side.BUY, MID - level * TICK, 10);
                place(2 + k, Side.SELL, MID + level * TICK, 10);
            }
        }
    }

    private void onEvent(ExchangeEvent event) {
        if (event instanceof OrderAccepted a) {
            lastOrderId = a.orderId();
        }
        sink.consume(event);
    }

    private void place(long account, Side side, long price, long quantity) {
        engine.apply(
                new NewOrder(ids[next++ & (ids.length - 1)], account, "INFY", side, OrderType.LIMIT, price, quantity));
    }

    /** A passive limit order joins the best bid, then is cancelled: the bread and butter of a market maker. */
    @Benchmark
    public void placeAndCancelPassive() {
        place(1, Side.BUY, MID - TICK, 10);
        engine.apply(new CancelOrder("x", 1, "INFY", lastOrderId));
    }

    /** An aggressive order takes 10 from the best ask; a new ask puts the quantity back. */
    @Benchmark
    public void aggressiveFillAndRefill() {
        place(1, Side.BUY, MID + TICK, 10);
        place(7, Side.SELL, MID + TICK, 10);
    }

    /** A market order sweeps three levels; the levels are refilled. */
    @Benchmark
    public void marketSweepThreeLevels() {
        engine.apply(new NewOrder(ids[next++ & (ids.length - 1)], 1, "INFY", Side.BUY, OrderType.MARKET, 0, 120));
        for (int level = 1; level <= 3; level++) {
            for (int k = 0; k < 4; k++) {
                place(2 + k, Side.SELL, MID + level * TICK, 10);
            }
        }
    }
}
