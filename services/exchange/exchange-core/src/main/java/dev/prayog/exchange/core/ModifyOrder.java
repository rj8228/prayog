package dev.prayog.exchange.core;

import java.util.Objects;

/**
 * Change a resting order's price and/or total quantity. {@code quantity} is the new <em>total</em>, including what
 * has already filled (the usual convention, as in FIX).
 */
public record ModifyOrder(String clientOrderId, long accountId, String symbol, long orderId, long price, long quantity)
        implements Command {

    public ModifyOrder {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(symbol, "symbol");
    }
}
