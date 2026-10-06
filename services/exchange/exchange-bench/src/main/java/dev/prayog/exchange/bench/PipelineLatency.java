package dev.prayog.exchange.bench;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.SetSessionState;
import dev.prayog.exchange.core.journal.EngineSetup;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.ExchangePipeline;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.HdrHistogram.Histogram;

/**
 * End-to-end latency of the order path without the network (S9): submit → matching → journal (with fsync) → the
 * stage that would answer the client. Commands are sent at a fixed rate, and latency is measured from each command's
 * <i>intended</i> send time, not the moment it was actually sent. If the pipeline stalls, the commands that should
 * have gone out during the stall count their wait too; measuring from the actual send would hide it ("coordinated
 * omission").
 *
 * <pre>
 * make bench-latency                      # 20,000 commands/s for 20 s, BLOCKING wait strategy
 * make bench-latency ARGS="50000 20 BUSY_SPIN"
 * make bench-latency ARGS="20000 20 BLOCKING nojournal"   # the same without the journal stage
 * </pre>
 */
public final class PipelineLatency {

    private static final long MID = 150_000;
    private static final Instrument INFY = new Instrument("INFY", 5, 1_000_000, MID, 20);

    private PipelineLatency() {}

    public static void main(String[] args) throws Exception {
        int rate = args.length > 0 ? Integer.parseInt(args[0]) : 20_000;
        int seconds = args.length > 1 ? Integer.parseInt(args[1]) : 20;
        WaitStrategyType wait = args.length > 2 ? WaitStrategyType.valueOf(args[2]) : WaitStrategyType.BLOCKING;
        boolean journaled = args.length <= 3 || !args[3].equals("nojournal");
        int warmupSeconds = 5;

        Path dir = Files.createTempDirectory("prayog-latency");
        Histogram histogram = new Histogram(TimeUnit.SECONDS.toNanos(10), 3);
        long[] measureFrom = {Long.MAX_VALUE};
        long[] completed = {0};
        PipelineHandler answer = (CommandSlot slot, boolean endOfBatch) -> {
            if (slot.context() instanceof Long intended) {
                long now = System.nanoTime();
                if (intended >= measureFrom[0]) {
                    histogram.recordValue(Math.max(0, now - intended));
                    completed[0]++;
                }
            }
        };

        EngineSetup setup = new EngineSetup(List.of(INFY), null);
        String[] ids = new String[1 << 15];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "c" + i;
        }
        JournalHandler journal = journaled ? JournalHandler.create(dir, setup) : null;
        ExchangePipeline.Builder builder = ExchangePipeline.builder(new PipelineConfig(65_536, wait), setup::newEngine);
        if (journal != null) {
            builder.then(journal);
        }
        try (ExchangePipeline pipeline = builder.then(answer).start()) {
            pipeline.submit(new ClockTick(1_790_000_000_000_000L));
            pipeline.submit(new SetRules(MatchingEngine.LATEST_RULES));
            pipeline.submit(new SetSessionState(SessionState.OPEN));

            long interval = TimeUnit.SECONDS.toNanos(1) / rate;
            long start = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100);
            measureFrom[0] = start + TimeUnit.SECONDS.toNanos(warmupSeconds);
            long total = (long) rate * (warmupSeconds + seconds);
            for (long i = 0; i < total; i++) {
                long intended = start + i * interval;
                while (System.nanoTime() < intended) {
                    Thread.onSpinWait();
                }
                // A resting sell, then a buy that fills it: the book stays the same size for the whole run.
                boolean sell = (i & 1) == 0;
                pipeline.submit(
                        new NewOrder(
                                ids[(int) (i & (ids.length - 1))],
                                sell ? 1 : 2,
                                "INFY",
                                sell ? Side.SELL : Side.BUY,
                                OrderType.LIMIT,
                                MID,
                                10),
                        intended);
            }
        }

        System.out.printf(
                "rate %,d commands/s for %d s (after %d s warm-up), wait strategy %s, journal %s%n",
                rate, seconds, warmupSeconds, wait, journaled ? "on (fsync per batch)" : "off");
        System.out.printf("measured %,d commands%n", completed[0]);
        for (double p : new double[] {50, 90, 99, 99.9, 99.99}) {
            System.out.printf("p%-6s %8.1f µs%n", p, histogram.getValueAtPercentile(p) / 1_000.0);
        }
        System.out.printf("max     %8.1f µs%n", histogram.getMaxValue() / 1_000.0);
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
