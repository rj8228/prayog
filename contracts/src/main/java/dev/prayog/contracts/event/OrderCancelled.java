package dev.prayog.contracts.event;

import dev.prayog.contracts.CancelReason;
import java.util.Objects;

/** The open part of an order ({@code cancelledQuantity}) was removed from the book. */
public record OrderCancelled(
        long seq,
        long simTime,
        long orderId,
        long accountId,
        String symbol,
        long cancelledQuantity,
        CancelReason reason)
        implements ExchangeEvent {

    public OrderCancelled {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(reason, "reason");
    }
}
