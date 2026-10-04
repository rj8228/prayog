package dev.prayog.exchange.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepeatedTest;

/**
 * Closing a pipeline must process everything already published, even if it is closed the instant it started.
 * Disruptor's shutdown only waits for consumers that are already running; a consumer whose thread has not started
 * yet counts as stopped and would be halted with work still in the ring. CI's slower machines hit this.
 */
class PipelineShutdownTest {

    private static final Instrument ABC = new Instrument("ABC", 5, 1_000, 10_000, 20);

    @RepeatedTest(300)
    void closeRightAfterStartStillProcessesEverything() {
        AtomicLong processed = new AtomicLong();
        ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(64, WaitStrategyType.BLOCKING),
                        sink -> new MatchingEngine(List.of(ABC), sink))
                .then((slot, endOfBatch) -> processed.incrementAndGet())
                .start();
        for (int i = 0; i < 20; i++) {
            pipeline.submit(new ClockTick(i));
        }
        pipeline.close();

        assertThat(processed.get()).isEqualTo(20);
    }
}
