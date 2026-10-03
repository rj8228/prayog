package dev.prayog.exchange.core.pipeline;

import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.BusySpinWaitStrategy;
import com.lmax.disruptor.WaitStrategy;
import com.lmax.disruptor.YieldingWaitStrategy;

/** How an idle handler thread waits for the next slot: a trade-off between latency and CPU use. */
public enum WaitStrategyType {
    /** Sleeps on a lock until woken. Lowest CPU, adds wake-up latency. Default for laptops and CI. */
    BLOCKING,
    /** Spins briefly, then yields the CPU. Middle ground. */
    YIELDING,
    /** Spins forever on a dedicated core. Lowest latency, burns a whole core. For benchmarks. */
    BUSY_SPIN;

    WaitStrategy create() {
        return switch (this) {
            case BLOCKING -> new BlockingWaitStrategy();
            case YIELDING -> new YieldingWaitStrategy();
            case BUSY_SPIN -> new BusySpinWaitStrategy();
        };
    }
}
