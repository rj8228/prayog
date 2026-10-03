package dev.prayog.contracts.event;

import java.util.Objects;

/** Price or quantity changed. {@code quantity} is the new total; {@code leavesQuantity} is what is still open. */
public record OrderModified(
        long seq,
        long simTime,
        long orderId,
        long accountId,
        String symbol,
        long price,
        long quantity,
        long leavesQuantity)
        implements ExchangeEvent {

    public OrderModified {
        Objects.requireNonNull(symbol, "symbol");
    }
}
