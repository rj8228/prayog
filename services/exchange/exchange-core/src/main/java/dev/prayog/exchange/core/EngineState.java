package dev.prayog.exchange.core;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import java.util.List;

/**
 * Everything a {@link MatchingEngine} remembers, as plain values: what a snapshot stores so recovery can start from it
 * instead of replaying the whole input journal (ADR 0016). The instruments and schedule are not here; they come from
 * the journal's {@code EngineSetup} record.
 *
 * <p>Lists are in a fixed, deterministic order, so the same engine state always gives the same snapshot bytes:
 * accounts ascending, orders by symbol, then bids before asks, best price first, and time priority within a price.
 */
public record EngineState(
        int rulesVersion,
        SessionState session,
        long simTime,
        boolean ticked,
        long nextEventSeq,
        long nextOrderId,
        long nextTradeId,
        List<Long> disabledAccounts,
        List<AccountClientOrderIds> clientOrderIds,
        List<Order> orders) {

    public EngineState {
        disabledAccounts = List.copyOf(disabledAccounts);
        clientOrderIds = List.copyOf(clientOrderIds);
        orders = List.copyOf(orders);
    }

    /** The client order IDs one account used today, oldest first (ADR 0014). */
    public record AccountClientOrderIds(long accountId, List<String> ids) {
        public AccountClientOrderIds {
            ids = List.copyOf(ids);
        }
    }

    /** One resting order, in its queue position. */
    public record Order(
            String symbol, long orderId, long accountId, Side side, long price, long quantity, long leavesQuantity) {}
}
