package dev.prayog.exchange.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SetSessionState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ExchangePipelineTest {

    private static final String ABC = "ABC";
    private static final Instrument INSTRUMENT = new Instrument(ABC, 5, 1_000, 10_000, 20);
    private static final long T0 = 1_790_000_000_000_000L;

    private record Recorded(long inputSeq, Command command, List<ExchangeEvent> events) {}

    /** A downstream stage that copies everything it sees (slots are reused, so it must copy). */
    private static final class Recorder implements PipelineHandler {
        private final List<Recorded> seen = new ArrayList<>();

        @Override
        public synchronized void onSlot(CommandSlot slot, boolean endOfBatch) {
            seen.add(new Recorded(slot.inputSeq(), slot.command(), List.copyOf(slot.events())));
        }

        synchronized List<Recorded> seen() {
            return List.copyOf(seen);
        }
    }

    private static MatchingEngine engine(dev.prayog.exchange.core.EventSink sink) {
        return new MatchingEngine(List.of(INSTRUMENT), sink);
    }

    /**
     * The S7 acceptance test. Eight threads submit 2,000 commands each at the same moment, so their arrival order is
     * different on every run. Whatever order the ring produced, it must be (1) gap-free, (2) faithful to each
     * thread's own submission order, and (3) reproducible: replaying the recorded inputs through a fresh engine on one
     * thread gives exactly the same events.
     */
    @Test
    void concurrentSubmitsGetOneDeterministicOrder() throws Exception {
        int threads = 8;
        int perThread = 2_000;
        Recorder recorder = new Recorder();
        ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(1_024, WaitStrategyType.BLOCKING), ExchangePipelineTest::engine)
                .then(recorder)
                .start();
        pipeline.submit(new ClockTick(T0));
        pipeline.submit(new SetSessionState(SessionState.OPEN));

        CountDownLatch go = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            Thread producer = new Thread(() -> {
                Random random = new Random(id);
                awaitQuietly(go);
                for (int i = 0; i < perThread; i++) {
                    pipeline.submit(randomCommand(random, id, i));
                }
            });
            producers.add(producer);
            producer.start();
        }
        go.countDown();
        for (Thread producer : producers) {
            producer.join();
        }
        pipeline.close();

        List<Recorded> seen = recorder.seen();
        int total = threads * perThread + 2;
        assertThat(seen).hasSize(total);
        for (int i = 0; i < total; i++) {
            assertThat(seen.get(i).inputSeq()).isEqualTo(i + 1L);
        }
        for (int t = 0; t < threads; t++) {
            String prefix = "t" + t + "-";
            List<Integer> order = seen.stream()
                    .map(r -> clientOrderId(r.command()))
                    .filter(c -> c != null && c.startsWith(prefix))
                    .map(c -> Integer.parseInt(c.substring(prefix.length())))
                    .toList();
            assertThat(order).as("thread %d kept its own order", t).isSorted().hasSize(perThread);
        }

        List<ExchangeEvent> replayed = new ArrayList<>();
        MatchingEngine fresh = engine(replayed::add);
        seen.stream().sorted(Comparator.comparingLong(Recorded::inputSeq)).forEach(r -> fresh.apply(r.command()));
        List<ExchangeEvent> live =
                seen.stream().flatMap(r -> r.events().stream()).toList();
        assertThat(replayed).isEqualTo(live);
        assertThat(live).hasSizeGreaterThan(total); // trades happened, not just accepts
    }

    @Test
    void laterStagesSeeEachSlotOnlyAfterEarlierStages() throws Exception {
        AtomicLong journalled = new AtomicLong();
        AtomicLong violations = new AtomicLong();
        ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(256, WaitStrategyType.BLOCKING), ExchangePipelineTest::engine)
                .then((slot, endOfBatch) -> journalled.set(slot.inputSeq()))
                .then((slot, endOfBatch) -> {
                    if (journalled.get() < slot.inputSeq()) {
                        violations.incrementAndGet();
                    }
                })
                .start();
        for (int i = 0; i < 10_000; i++) {
            pipeline.submit(new ClockTick(T0 + i));
        }
        pipeline.close();

        assertThat(journalled.get()).isEqualTo(10_000);
        assertThat(violations.get()).isZero();
    }

    @Test
    void trySubmitRefusesWhenTheRingIsFull() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        AtomicLong processed = new AtomicLong();
        ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(16, WaitStrategyType.BLOCKING), ExchangePipelineTest::engine)
                .then((slot, endOfBatch) -> {
                    release.await(); // a stuck downstream stage, like a slow disk
                    processed.incrementAndGet();
                })
                .start();
        int accepted = 0;
        boolean refused = false;
        for (int i = 0; i < 100 && !refused; i++) {
            if (pipeline.trySubmit(new ClockTick(T0 + i))) {
                accepted++;
            } else {
                refused = true;
            }
        }
        release.countDown();
        pipeline.close();

        assertThat(refused).isTrue();
        assertThat(accepted).isLessThanOrEqualTo(16);
        assertThat(processed.get()).isEqualTo(accepted);
    }

    @Test
    void clockTickerPublishesSimTime() throws Exception {
        Recorder recorder = new Recorder();
        ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(64, WaitStrategyType.BLOCKING), ExchangePipelineTest::engine)
                .then(recorder)
                .start();
        AtomicLong wall = new AtomicLong();
        SimClock clock = new SimClock(T0, 10, wall::get);
        try (ClockTicker ticker = new ClockTicker(clock, pipeline, 1)) {
            ticker.tick();
            wall.addAndGet(1_000_000); // 1 ms wall = 10 ms sim
            ticker.tick();
            ticker.start();
            Thread.sleep(20); // the thread adds a few more ticks
        }
        pipeline.close();

        List<Recorded> seen = recorder.seen();
        assertThat(seen.get(0).command()).isEqualTo(new ClockTick(T0));
        assertThat(seen.get(1).command()).isEqualTo(new ClockTick(T0 + 10_000));
        assertThat(seen).hasSizeGreaterThan(2);
    }

    private static Command randomCommand(Random random, int thread, int i) {
        String clientOrderId = "t" + thread + "-" + i;
        long account = thread + 1;
        if (random.nextInt(10) == 0) {
            return new CancelOrder(clientOrderId, account, ABC, 1 + random.nextInt(5_000));
        }
        Side side = random.nextBoolean() ? Side.BUY : Side.SELL;
        long price = (1_995 + random.nextInt(11)) * 5L;
        return new NewOrder(clientOrderId, account, ABC, side, OrderType.LIMIT, price, 1 + random.nextInt(20));
    }

    private static String clientOrderId(Command command) {
        return switch (command) {
            case NewOrder o -> o.clientOrderId();
            case CancelOrder c -> c.clientOrderId();
            default -> null;
        };
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
