package dev.prayog.exchange.app.api;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Request and response bodies of the REST API. Prices are integer paise; times are sim time in epoch micros. */
public final class ApiTypes {

    private ApiTypes() {}

    /** {@code price} is required for LIMIT and ignored for MARKET; {@code clientOrderId} is generated if missing. */
    public record PlaceOrder(
            String symbol, Side side, OrderType type, Long price, Long quantity, String clientOrderId) {}

    /** New price and new total quantity (FIX convention: quantity includes what already filled). */
    public record ModifyOrder(Long price, Long quantity, String clientOrderId) {}

    public record FillView(long tradeId, long price, long quantity) {}

    /**
     * Outcome of one request, after it is journaled. {@code status}: {@code resting} (on the book, maybe partly
     * filled), {@code filled}, {@code cancelled} (nothing left open, e.g. a market order's unfilled rest),
     * {@code modified} or {@code rejected} with {@code reason}.
     */
    public record OrderResult(
            String status,
            long orderId,
            String clientOrderId,
            String symbol,
            String reason,
            long filledQuantity,
            long leavesQuantity,
            List<FillView> fills,
            long inputSeq,
            long simTime) {}

    public record OpenOrderView(
            long orderId,
            String clientOrderId,
            String symbol,
            Side side,
            long price,
            long quantity,
            long leavesQuantity,
            long filledQuantity) {}

    public record CancelAllResult(int requested, int cancelled) {}

    public record Me(String subject, String accountLabel, long accountId, String clientId, Set<String> roles) {}

    public record InstrumentView(
            String symbol,
            long tickSize,
            long maxOrderQuantity,
            long referencePrice,
            int bandPercent,
            long bandLow,
            long bandHigh) {}

    public record SessionView(
            SessionState state, long simTime, int clockMultiplier, LocalTime open, LocalTime close, String offset) {}

    public record SetSession(SessionState state) {}

    public record SetClock(Integer multiplier) {}

    public record SetAccount(Boolean enabled) {}

    public record ErrorBody(String error, String message) {}
}
