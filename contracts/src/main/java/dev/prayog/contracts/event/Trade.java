package dev.prayog.contracts.event;

import dev.prayog.contracts.Side;
import java.util.Objects;

/**
 * Two orders matched. {@code price} is the resting order's price; {@code aggressorSide} is the side of the incoming
 * order that took liquidity.
 */
public record Trade(
        long seq,
        long simTime,
        long tradeId,
        String symbol,
        long price,
        long quantity,
        Side aggressorSide,
        long buyOrderId,
        long sellOrderId,
        long buyAccountId,
        long sellAccountId)
        implements ExchangeEvent {

    public Trade {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(aggressorSide, "aggressorSide");
    }
}
