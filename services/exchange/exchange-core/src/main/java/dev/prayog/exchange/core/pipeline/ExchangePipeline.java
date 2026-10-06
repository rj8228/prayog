package dev.prayog.exchange.core.pipeline;

import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.EventTranslatorTwoArg;
import com.lmax.disruptor.FatalExceptionHandler;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.EventHandlerGroup;
import com.lmax.disruptor.dsl.ProducerType;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.EventSink;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.TakeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Many producers → one ring buffer → one matching thread → downstream stages.
 *
 * <pre>
 *   ExchangePipeline pipeline = ExchangePipeline.builder(config, sink -&gt; new MatchingEngine(instruments, sink))
 *       .then(journal)                // stage 1: runs after matching
 *       .then(marketData, responses)  // stage 2: after the journal, in parallel with each other
 *       .start();
 * </pre>
 *
 * <p>The matching thread is the only thread that touches the engine, so the engine needs no locks. If a handler
 * throws, its thread stops (fail safe): an engine bug or a journal failure must halt trading, not skip a command.
 */
public final class ExchangePipeline implements AutoCloseable {

    private static final EventTranslatorTwoArg<CommandSlot, Command, Object> WRITE_COMMAND =
            (slot, sequence, command, context) -> {
                slot.command = command;
                slot.context = context;
            };

    private final Disruptor<CommandSlot> disruptor;
    private final RingBuffer<CommandSlot> ring;

    private ExchangePipeline(Disruptor<CommandSlot> disruptor) {
        this.disruptor = disruptor;
        this.ring = disruptor.start();
    }

    public static Builder builder(PipelineConfig config, Function<EventSink, MatchingEngine> engineFactory) {
        return new Builder(config, engineFactory);
    }

    /**
     * Publishes a command, waiting while the ring is full. For internal producers (the clock, ops) that must not lose
     * a command.
     */
    public void submit(Command command) {
        submit(command, null);
    }

    /** As {@link #submit(Command)}, with a context that later stages read from {@link CommandSlot#context()}. */
    public void submit(Command command, Object context) {
        ring.publishEvent(WRITE_COMMAND, Objects.requireNonNull(command, "command"), context);
    }

    /**
     * Publishes a command if there is room. Returns false when the ring is full, so the gateway can answer "busy"
     * (back-pressure) instead of queueing without limit.
     */
    public boolean trySubmit(Command command) {
        return trySubmit(command, null);
    }

    /** As {@link #trySubmit(Command)}, with a context that later stages read from {@link CommandSlot#context()}. */
    public boolean trySubmit(Command command, Object context) {
        return ring.tryPublishEvent(WRITE_COMMAND, Objects.requireNonNull(command, "command"), context);
    }

    /** Free slots in the ring right now. Near zero means a later stage is falling behind (back-pressure). */
    public long remainingCapacity() {
        return ring.remainingCapacity();
    }

    /** Total slots in the ring. */
    public int capacity() {
        return ring.getBufferSize();
    }

    /** Processes everything already published, then stops all handler threads. */
    @Override
    public void close() {
        disruptor.shutdown();
    }

    public static final class Builder {

        private final PipelineConfig config;
        private final Function<EventSink, MatchingEngine> engineFactory;
        private final List<PipelineHandler[]> stages = new ArrayList<>();
        private long lastInputSeq;

        private Builder(PipelineConfig config, Function<EventSink, MatchingEngine> engineFactory) {
            this.config = Objects.requireNonNull(config, "config");
            this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        }

        /**
         * Continues an existing session: the first command processed gets {@code lastInputSeq + 1}. Used after the
         * engine has been rebuilt from a journal that already holds commands up to {@code lastInputSeq}.
         */
        public Builder continueAfter(long lastInputSeq) {
            if (lastInputSeq < 0) {
                throw new IllegalArgumentException("lastInputSeq must not be negative: " + lastInputSeq);
            }
            this.lastInputSeq = lastInputSeq;
            return this;
        }

        /** Adds a stage that runs after all previous stages. Handlers within one stage run in parallel. */
        public Builder then(PipelineHandler... handlers) {
            if (handlers.length == 0) {
                throw new IllegalArgumentException("a stage needs at least one handler");
            }
            stages.add(handlers.clone());
            return this;
        }

        @SuppressWarnings("unchecked")
        public ExchangePipeline start() {
            Disruptor<CommandSlot> disruptor = new Disruptor<>(
                    CommandSlot::new,
                    config.ringSize(),
                    new NamedThreads(),
                    ProducerType.MULTI, // gateway threads, the clock and ops all publish
                    config.waitStrategy().create());
            disruptor.setDefaultExceptionHandler(new FatalExceptionHandler());
            int consumers = 1 + stages.stream().mapToInt(stage -> stage.length).sum();
            CountDownLatch running = new CountDownLatch(consumers);
            EventHandlerGroup<CommandSlot> group =
                    disruptor.handleEventsWith(new MatchingHandler(engineFactory, lastInputSeq, running));
            for (PipelineHandler[] stage : stages) {
                EventHandler<CommandSlot>[] adapted = new EventHandler[stage.length];
                for (int i = 0; i < stage.length; i++) {
                    adapted[i] = new StageHandler(stage[i], running);
                }
                group = group.then(adapted);
            }
            ExchangePipeline pipeline = new ExchangePipeline(disruptor);
            // Disruptor's shutdown only waits for consumers that are already running; one whose thread has not started
            // yet counts as stopped and would be halted with work still in the ring. So start() returns only once every
            // consumer thread is running, and close() can always drain everything published.
            try {
                if (!running.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("pipeline threads did not start within 10 s");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while starting the pipeline", e);
            }
            return pipeline;
        }
    }

    /**
     * The single writer. Assigns the input sequence on the matching thread (BUILD_PLAN 16.1 #13), so the number and
     * the processing order can never disagree, then collects the command's events into its slot.
     */
    private static final class MatchingHandler implements EventHandler<CommandSlot> {

        private final MatchingEngine engine;
        private CommandSlot current;
        private long inputSeq;

        private final CountDownLatch running;

        MatchingHandler(Function<EventSink, MatchingEngine> engineFactory, long lastInputSeq, CountDownLatch running) {
            this.running = running;
            EventSink intoCurrentSlot = (ExchangeEvent event) -> current.events.add(event);
            this.engine = engineFactory.apply(intoCurrentSlot);
            this.inputSeq = lastInputSeq;
        }

        @Override
        public void onStart() {
            running.countDown();
        }

        @Override
        public void onEvent(CommandSlot slot, long sequence, boolean endOfBatch) {
            slot.events.clear(); // the slot is being reused
            slot.inputSeq = ++inputSeq;
            current = slot;
            engine.apply(slot.command);
            // On the matching thread, so the copy is exactly the state after this input seq (ADR 0016).
            slot.snapshot = slot.command instanceof TakeSnapshot ? engine.snapshot() : null;
        }
    }

    /** Adapts a {@link PipelineHandler} to the Disruptor and reports when its thread is running. */
    private static final class StageHandler implements EventHandler<CommandSlot> {

        private final PipelineHandler handler;
        private final CountDownLatch running;

        StageHandler(PipelineHandler handler, CountDownLatch running) {
            this.handler = handler;
            this.running = running;
        }

        @Override
        public void onStart() {
            running.countDown();
        }

        @Override
        public void onEvent(CommandSlot slot, long sequence, boolean endOfBatch) throws Exception {
            handler.onSlot(slot, endOfBatch);
        }
    }

    private static final class NamedThreads implements ThreadFactory {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            int n = count.getAndIncrement();
            Thread thread = new Thread(task, n == 0 ? "prayog-matching" : "prayog-stage-" + n);
            thread.setDaemon(true);
            return thread;
        }
    }
}
