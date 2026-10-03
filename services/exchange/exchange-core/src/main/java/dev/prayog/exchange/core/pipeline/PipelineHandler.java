package dev.prayog.exchange.core.pipeline;

/** A downstream stage of the pipeline, such as the journal or a publisher. Runs on its own thread. */
@FunctionalInterface
public interface PipelineHandler {

    /**
     * Handles one slot, after matching (and after every earlier stage) has finished with it.
     *
     * @param endOfBatch true for the last slot currently available; the journal fsyncs here, so one disk flush covers
     *     the whole batch ("group commit")
     */
    void onSlot(CommandSlot slot, boolean endOfBatch) throws Exception;
}
