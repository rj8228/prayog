package dev.prayog.exchange.core;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Applies exchange rules to incoming commands and emits events. Price-time priority: the best price trades first, and
 * at one price the oldest order trades first. A trade always happens at the resting order's price.
 *
 * <p>Single-threaded and deterministic: it owns every counter, takes time only from {@link ClockTick} commands, and
 * never iterates a hash map, so the same commands always give the same events.
 */
public final class MatchingEngine {

    private final Map<String, Instrument> instruments = new HashMap<>();
    private final Map<String, OrderBook> books = new HashMap<>();
    private final EventSink sink;

    private long simTime;
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

    /** Applies one command. Every outcome, including a refusal, is reported as events. */
    public void apply(Command command) {
        switch (command) {
            case NewOrder order -> newOrder(order);
            case CancelOrder cancel -> cancel(cancel);
            case ModifyOrder modify -> modify(modify);
            case ClockTick tick -> simTime = Math.max(simTime, tick.simTime()); // ticks never move time backwards
            case SetSessionState state -> throw new UnsupportedOperationException("Sessions arrive in S6");
            case SetAccountEnabled account -> throw new UnsupportedOperationException("Kill switch arrives in S6");
        }
    }

    private void newOrder(NewOrder order) {
        Instrument instrument = instruments.get(order.symbol());
        RejectReason reason = instrument == null ? RejectReason.UNKNOWN_SYMBOL : validateNew(order, instrument);
        if (reason != null) {
            reject(0, order.clientOrderId(), order.accountId(), order.symbol(), reason);
            return;
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
        boolean market = order.type() == OrderType.MARKET;
        long limit = market ? marketLimit(order.side()) : order.price();
        long leaves = match(book, orderId, order.accountId(), order.side(), limit, order.quantity());
        if (leaves == 0) {
            return;
        }
        if (market) {
            // A market order never rests: whatever the book could not fill is cancelled.
            cancelled(orderId, order.accountId(), book.symbol(), leaves, CancelReason.NO_LIQUIDITY);
        } else {
            book.add(new RestingOrder(
                    orderId, order.accountId(), order.side(), order.price(), order.quantity(), leaves));
        }
    }

    private void cancel(CancelOrder cancel) {
        OrderBook book = books.get(cancel.symbol());
        RestingOrder order = book == null ? null : ownOrder(book, cancel.orderId(), cancel.accountId());
        if (order == null) {
            reject(cancel.orderId(), cancel.clientOrderId(), cancel.accountId(), cancel.symbol(), missing(book));
            return;
        }
        long open = order.leavesQuantity;
        book.remove(order);
        cancelled(order.orderId, order.accountId, book.symbol(), open, CancelReason.CLIENT_REQUEST);
    }

    /**
     * Priority rules: reducing quantity at the same price keeps the order's place in the queue (it harms nobody
     * behind it). Raising quantity or changing price is treated like a new order: back of the queue, and it may trade
     * at once if the new price crosses.
     */
    private void modify(ModifyOrder modify) {
        OrderBook book = books.get(modify.symbol());
        RestingOrder order = book == null ? null : ownOrder(book, modify.orderId(), modify.accountId());
        if (order == null) {
            reject(modify.orderId(), modify.clientOrderId(), modify.accountId(), modify.symbol(), missing(book));
            return;
        }
        RejectReason reason = validateModify(modify, instruments.get(modify.symbol()));
        if (reason != null) {
            reject(modify.orderId(), modify.clientOrderId(), modify.accountId(), modify.symbol(), reason);
            return;
        }

        long filled = order.quantity - order.leavesQuantity;
        if (modify.quantity() <= filled) {
            // Nothing would be left open (BUILD_PLAN 16.3): cancel the open part.
            long open = order.leavesQuantity;
            book.remove(order);
            cancelled(order.orderId, order.accountId, book.symbol(), open, CancelReason.MODIFIED_TO_ZERO);
            return;
        }

        long newLeaves = modify.quantity() - filled;
        if (modify.price() == order.price && modify.quantity() <= order.quantity) {
            book.reduce(order, order.leavesQuantity - newLeaves);
            order.quantity = modify.quantity();
            modified(order, modify, newLeaves);
            return;
        }

        book.remove(order);
        modified(order, modify, newLeaves);
        long leaves = match(book, order.orderId, order.accountId, order.side, modify.price(), newLeaves);
        if (leaves > 0) {
            book.add(new RestingOrder(
                    order.orderId, order.accountId, order.side, modify.price(), modify.quantity(), leaves));
        }
    }

    /**
     * Trades an incoming quantity against the opposite side while prices cross. Returns the unfilled quantity. The
     * incoming order is not on the book while it matches.
     */
    private long match(OrderBook book, long orderId, long accountId, Side side, long limit, long quantity) {
        long leaves = quantity;
        PriceLevel level;
        while (leaves > 0 && (level = book.bestLevel(side.opposite())) != null && crosses(side, limit, level)) {
            RestingOrder resting = level.head();
            long fill = Math.min(leaves, resting.leavesQuantity);
            boolean buying = side == Side.BUY;
            sink.accept(new Trade(
                    nextEventSeq++,
                    simTime,
                    nextTradeId++,
                    book.symbol(),
                    resting.price,
                    fill,
                    side,
                    buying ? orderId : resting.orderId,
                    buying ? resting.orderId : orderId,
                    buying ? accountId : resting.accountId,
                    buying ? resting.accountId : accountId));
            leaves -= fill;
            book.fill(resting, fill);
        }
        return leaves;
    }

    /** A buy crosses an ask at or below its limit; a sell crosses a bid at or above its limit. */
    private static boolean crosses(Side side, long limit, PriceLevel opposite) {
        return side == Side.BUY ? opposite.price <= limit : opposite.price >= limit;
    }

    /** The worst price a market order accepts. Unbounded until price bands arrive in S6. */
    private static long marketLimit(Side side) {
        return side == Side.BUY ? Long.MAX_VALUE : 0;
    }

    /** Another account's order is reported exactly like a missing one, so its existence is not revealed. */
    private static RestingOrder ownOrder(OrderBook book, long orderId, long accountId) {
        RestingOrder order = book.order(orderId);
        return order != null && order.accountId == accountId ? order : null;
    }

    private static RejectReason missing(OrderBook book) {
        return book == null ? RejectReason.UNKNOWN_SYMBOL : RejectReason.UNKNOWN_ORDER;
    }

    /** First failing check wins, so a reject always has one clear reason. */
    private static RejectReason validateNew(NewOrder order, Instrument instrument) {
        if (order.quantity() <= 0 || order.quantity() > instrument.maxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (order.type() == OrderType.MARKET) {
            return order.price() == 0 ? null : RejectReason.INVALID_PRICE;
        }
        return validatePrice(order.price(), instrument);
    }

    private static RejectReason validateModify(ModifyOrder modify, Instrument instrument) {
        if (modify.quantity() <= 0 || modify.quantity() > instrument.maxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        return validatePrice(modify.price(), instrument);
    }

    private static RejectReason validatePrice(long price, Instrument instrument) {
        if (price <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (price % instrument.tickSize() != 0) {
            return RejectReason.PRICE_NOT_ON_TICK;
        }
        return null;
    }

    private void reject(long orderId, String clientOrderId, long accountId, String symbol, RejectReason reason) {
        sink.accept(new OrderRejected(nextEventSeq++, simTime, orderId, clientOrderId, accountId, symbol, reason));
    }

    private void cancelled(long orderId, long accountId, String symbol, long quantity, CancelReason reason) {
        sink.accept(new OrderCancelled(nextEventSeq++, simTime, orderId, accountId, symbol, quantity, reason));
    }

    private void modified(RestingOrder order, ModifyOrder modify, long leaves) {
        sink.accept(new OrderModified(
                nextEventSeq++,
                simTime,
                order.orderId,
                order.accountId,
                modify.symbol(),
                modify.price(),
                modify.quantity(),
                leaves));
    }
}
