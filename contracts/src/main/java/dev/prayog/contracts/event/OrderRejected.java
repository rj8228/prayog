package dev.prayog.contracts.event;

import dev.prayog.contracts.RejectReason;
import java.util.Objects;

/** A request was refused. {@code orderId} is 0 when the request did not name an existing order. */
public record OrderRejected(
        long seq, long simTime, long orderId, String clientOrderId, long accountId, String symbol, RejectReason reason)
        implements ExchangeEvent {

    public OrderRejected {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(reason, "reason");
    }
}
