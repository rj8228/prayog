package dev.prayog.exchange.app.core;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.app.account.AccountHub;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.core.pipeline.CommandSlot;
import dev.prayog.exchange.core.pipeline.PipelineHandler;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline stage 2, after the journal: everything it sees is already on disk. It answers the caller waiting for this
 * command, updates market data and sends private updates.
 *
 * <p>A failure in market data or the private feed is logged and counted, not thrown: thrown, it would stop this
 * stage, the ring would fill and trading would halt (ADR 0006) over a display problem. The journal stage, which
 * decides what really happened, still halts on any error.
 */
final class OutboundHandler implements PipelineHandler {

    private static final Logger log = LoggerFactory.getLogger(OutboundHandler.class);

    private final MarketHub market;
    private final AccountHub accounts;
    private final AtomicLong lastInputSeq = new AtomicLong();
    private final AtomicLong publishErrors = new AtomicLong();

    OutboundHandler(MarketHub market, AccountHub accounts, long lastInputSeq) {
        this.market = market;
        this.accounts = accounts;
        this.lastInputSeq.set(lastInputSeq);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void onSlot(CommandSlot slot, boolean endOfBatch) {
        List<ExchangeEvent> events = List.copyOf(slot.events()); // the slot is reused after this call
        try {
            market.apply(events);
            accounts.publish(events);
        } catch (RuntimeException e) {
            publishErrors.incrementAndGet();
            log.error("publishing events of input seq {} failed", slot.inputSeq(), e);
        }
        if (slot.context() instanceof CompletableFuture<?> pending) {
            ((CompletableFuture<CommandResult>) pending).complete(new CommandResult(slot.inputSeq(), events));
        }
        lastInputSeq.set(slot.inputSeq());
    }

    long lastInputSeq() {
        return lastInputSeq.get();
    }

    long publishErrors() {
        return publishErrors.get();
    }
}
