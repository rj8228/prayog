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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
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
            EventHandlerGroup<CommandSlot> group =
                    disruptor.handleEventsWith(new MatchingHandler(engineFactory, lastInputSeq));
            for (PipelineHandler[] stage : stages) {
                EventHandler<CommandSlot>[] adapted = new EventHandler[stage.length];
                for (int i = 0; i < stage.length; i++) {
                    PipelineHandler handler = stage[i];
                    adapted[i] = (slot, sequence, endOfBatch) -> handler.onSlot(slot, endOfBatch);
                }
                group = group.then(adapted);
            }
            return new ExchangePipeline(disruptor);
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

        MatchingHandler(Function<EventSink, MatchingEngine> engineFactory, long lastInputSeq) {
            EventSink intoCurrentSlot = (ExchangeEvent event) -> current.events.add(event);
            this.engine = engineFactory.apply(intoCurrentSlot);
            this.inputSeq = lastInputSeq;
        }

        @Override
        public void onEvent(CommandSlot slot, long sequence, boolean endOfBatch) {
            slot.events.clear(); // the slot is being reused
            slot.inputSeq = ++inputSeq;
            current = slot;
            engine.apply(slot.command);
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
