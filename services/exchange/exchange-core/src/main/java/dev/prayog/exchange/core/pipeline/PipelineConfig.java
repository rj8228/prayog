package dev.prayog.exchange.core.pipeline;

import java.util.Objects;

/**
 * @param ringSize number of slots; a power of two so the ring can map a sequence to a slot with a bit mask
 */
public record PipelineConfig(int ringSize, WaitStrategyType waitStrategy) {

    /** BUILD_PLAN 16.1 #11: 65,536 slots, blocking wait. */
    public static final PipelineConfig DEFAULT = new PipelineConfig(65_536, WaitStrategyType.BLOCKING);

    public PipelineConfig {
        Objects.requireNonNull(waitStrategy, "waitStrategy");
        if (ringSize < 2 || Integer.bitCount(ringSize) != 1) {
            throw new IllegalArgumentException("ringSize must be a power of two: " + ringSize);
        }
    }
}
