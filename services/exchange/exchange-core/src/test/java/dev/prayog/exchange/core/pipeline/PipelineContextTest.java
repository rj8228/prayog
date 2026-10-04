package dev.prayog.exchange.core.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.contracts.SessionState;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.SetSessionState;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class PipelineContextTest {

    private static final Instrument ABC = new Instrument("ABC", 5, 1_000, 10_000, 20);

    @Test
    void laterStagesReceiveTheContextAttachedToEachCommand() throws Exception {
        Map<Long, Object> seen = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(3);
        try (ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(16, WaitStrategyType.BLOCKING),
                        sink -> new MatchingEngine(List.of(ABC), sink))
                .then((slot, endOfBatch) -> {
                    if (slot.context() != null) {
                        seen.put(slot.inputSeq(), slot.context());
                    }
                    done.countDown();
                })
                .start()) {
            pipeline.submit(new ClockTick(1), "tick-request");
            assertThat(pipeline.trySubmit(new SetSessionState(SessionState.OPEN), "open-request"))
                    .isTrue();
            pipeline.submit(new ClockTick(2)); // no context
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seen).containsExactlyInAnyOrderEntriesOf(Map.of(1L, "tick-request", 2L, "open-request"));
    }

    @Test
    void aReusedSlotDoesNotLeakTheContextOfTheCommandBeforeIt() throws Exception {
        Map<Long, Object> seen = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(10);
        try (ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(2, WaitStrategyType.BLOCKING), // tiny ring: slots are reused at once
                        sink -> new MatchingEngine(List.of(ABC), sink))
                .then((slot, endOfBatch) -> {
                    seen.put(slot.inputSeq(), slot.context() == null ? "none" : slot.context());
                    done.countDown();
                })
                .start()) {
            // The first four carry a context and fill both slots; the next six reuse those slots without one.
            for (int i = 1; i <= 10; i++) {
                pipeline.submit(new ClockTick(i), i <= 4 ? "ctx-" + i : null);
            }
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }

        for (long seq = 1; seq <= 10; seq++) {
            assertThat(seen.get(seq)).isEqualTo(seq <= 4 ? "ctx-" + seq : "none");
        }
    }

    @Test
    void continuesTheInputSequenceOfARecoveredSession() throws Exception {
        Map<Long, Object> seen = new ConcurrentHashMap<>();
        CountDownLatch done = new CountDownLatch(2);
        try (ExchangePipeline pipeline = ExchangePipeline.builder(
                        new PipelineConfig(16, WaitStrategyType.BLOCKING),
                        sink -> new MatchingEngine(List.of(ABC), sink))
                .continueAfter(41)
                .then((slot, endOfBatch) -> {
                    seen.put(slot.inputSeq(), slot.command());
                    done.countDown();
                })
                .start()) {
            pipeline.submit(new ClockTick(1));
            pipeline.submit(new ClockTick(2));
            assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(seen).containsOnlyKeys(42L, 43L);
    }

    @Test
    void refusesANegativeStartingSequence() {
        assertThatThrownBy(() -> ExchangePipeline.builder(
                                new PipelineConfig(16, WaitStrategyType.BLOCKING),
                                sink -> new MatchingEngine(List.of(ABC), sink))
                        .continueAfter(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
