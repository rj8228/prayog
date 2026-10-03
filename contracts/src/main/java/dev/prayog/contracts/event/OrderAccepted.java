package dev.prayog.contracts.event;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import java.util.Objects;

/** The order passed all checks. {@code price} is 0 for a MARKET order. */
public record OrderAccepted(
        long seq,
        long simTime,
        long orderId,
        String clientOrderId,
        long accountId,
        String symbol,
        Side side,
        OrderType orderType,
        long price,
        long quantity)
        implements ExchangeEvent {

    public OrderAccepted {
        Objects.requireNonNull(clientOrderId, "clientOrderId");
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(orderType, "orderType");
    }
}
