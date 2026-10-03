package dev.prayog.exchange.core;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies exchange rules to incoming orders and emits events. Price-time priority: the best price trades first, and
 * at one price the oldest order trades first. A trade always happens at the resting order's price.
 *
 * <p>Single-threaded and deterministic: it owns every counter, reads time only from the {@code simTime} argument, and
 * never iterates a hash map, so the same inputs always give the same events.
 */
public final class MatchingEngine {

    private final Map<String, Instrument> instruments = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final EventSink sink;

    private long nextEventSeq = 1;
    private long nextOrderId = 1;
    private long nextTradeId = 1;

    public MatchingEngine(Collection<Instrument> instruments, EventSink sink) {
        this.sink = Objects.requireNonNull(sink, "sink");
        for (Instrument instrument : instruments) {
            this.instruments.put(instrument.symbol(), instrument);
            this.books.put(instrument.symbol(), new OrderBook(instrument.symbol()));
        }
    }

    /** The book for a symbol, or null if the symbol is not listed. */
    public OrderBook book(String symbol) {
        return books.get(symbol);
    }

    /** Validates, matches and (for a LIMIT order) rests the remainder. */
    public void submit(NewOrder order, long simTime) {
        RejectReason reason = validate(order);
        if (reason != null) {
            sink.accept(new OrderRejected(
                    nextEventSeq++, simTime, 0, order.clientOrderId(), order.accountId(), order.symbol(), reason));
            return;
        }
        if (order.type() == OrderType.MARKET) {
            throw new UnsupportedOperationException("Market orders arrive in session S5");
        }

        long orderId = nextOrderId++;
        sink.accept(new OrderAccepted(
                nextEventSeq++,
                simTime,
                orderId,
                order.clientOrderId(),
                order.accountId(),
                order.symbol(),
                order.side(),
                order.type(),
                order.price(),
                order.quantity()));

        OrderBook book = books.get(order.symbol());
        long leaves = match(book, orderId, order, simTime);
        if (leaves > 0) {
            book.add(new RestingOrder(orderId, order.accountId(), order.side(), order.price(), leaves));
        }
    }

    /** Trades the incoming order against the opposite side while prices cross. Returns the unfilled quantity. */
    private long match(OrderBook book, long orderId, NewOrder order, long simTime) {
        Side side = order.side();
        long leaves = order.quantity();
        PriceLevel level;
        while (leaves > 0 && (level = book.bestLevel(side.opposite())) != null && crosses(side, order.price(), level)) {
            RestingOrder resting = level.head();
            long quantity = Math.min(leaves, resting.leavesQuantity);
            boolean buying = side == Side.BUY;
            sink.accept(new Trade(
                    nextEventSeq++,
                    simTime,
                    nextTradeId++,
                    book.symbol(),
                    resting.price,
                    quantity,
                    side,
                    buying ? orderId : resting.orderId,
                    buying ? resting.orderId : orderId,
                    buying ? order.accountId() : resting.accountId,
                    buying ? resting.accountId : order.accountId()));
            leaves -= quantity;
            book.fill(resting, quantity);
        }
        return leaves;
    }

    /** A buy crosses an ask at or below its limit; a sell crosses a bid at or above its limit. */
    private static boolean crosses(Side side, long limit, PriceLevel opposite) {
        return side == Side.BUY ? opposite.price <= limit : opposite.price >= limit;
    }

    /** First failing check wins, so a reject always has one clear reason. */
    private RejectReason validate(NewOrder order) {
        Instrument instrument = instruments.get(order.symbol());
        if (instrument == null) {
            return RejectReason.UNKNOWN_SYMBOL;
        }
        if (order.quantity() <= 0 || order.quantity() > instrument.maxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (order.type() == OrderType.MARKET) {
            return order.price() == 0 ? null : RejectReason.INVALID_PRICE;
        }
        if (order.price() <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (order.price() % instrument.tickSize() != 0) {
            return RejectReason.PRICE_NOT_ON_TICK;
        }
        return null;
    }
}
