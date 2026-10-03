package dev.prayog.exchange.core;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import java.util.Objects;

/**
 * A request to enter an order, after the gateway has authenticated it. {@code price} is in paise and is 0 for a MARKET
 * order. Values are not trusted: the engine validates them and rejects with a reason.
 */
public record NewOrder(
        String clientOrderId, long accountId, String symbol, Side side, OrderType type, long price, long quantity) {

    public NewOrder {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(type, "type");
    }
}
