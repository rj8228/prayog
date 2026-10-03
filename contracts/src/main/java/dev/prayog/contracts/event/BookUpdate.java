package dev.prayog.contracts.event;

import dev.prayog.contracts.Side;
import java.util.Objects;

/** New total at one price level. {@code quantity == 0} means the level is gone. */
public record BookUpdate(long seq, long simTime, String symbol, Side side, long price, long quantity, int orderCount)
        implements ExchangeEvent {

    public BookUpdate {
        Objects.requireNonNull(symbol, "symbol");
        Objects.requireNonNull(side, "side");
    }
}
