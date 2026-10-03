package dev.prayog.exchange.core;

import java.util.Objects;

/** Remove the open part of an order. Only the order's own account may cancel it. */
public record CancelOrder(String clientOrderId, long accountId, String symbol, long orderId) implements Command {

    public CancelOrder {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(symbol, "symbol");
    }
}
