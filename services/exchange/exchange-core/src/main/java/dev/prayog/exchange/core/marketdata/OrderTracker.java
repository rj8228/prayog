package dev.prayog.exchange.core.marketdata;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.core.DepthLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Rebuilds the order book and every account's open orders from the engine's events alone, outside the matching
 * thread. Market data and "my open orders" are served from here, so nothing ever has to read the engine's own book
 * from another thread.
 *
 * <p>Feed it each command's events with {@link #onEvent}, then call {@link #endCommand}, which returns the price
 * levels whose total changed. Within a command the book can pass through states that never really existed (an
 * incoming order is counted before it trades); only the state between commands is real, and only it is reported.
 *
 * <p>Single-threaded, like the engine: the owner serialises access.
 */
public final class OrderTracker {

    /** One price level after a command: quantity 0 means the level is gone. */
    public record LevelChange(String symbol, Side side, long price, long quantity, int orderCount) {}

    /** An order with quantity still open on the book. */
    public record OpenOrder(
            long orderId,
            String clientOrderId,
            long accountId,
            String symbol,
            Side side,
            long price,
            long quantity,
            long leavesQuantity) {}

    private static final class Tracked {
        final long orderId;
        final String clientOrderId;
        final long accountId;
        final String symbol;
        final Side side;
        final boolean rests; // market orders never rest
        long price;
        long quantity;
        long leaves;

        Tracked(OrderAccepted a) {
            orderId = a.orderId();
            clientOrderId = a.clientOrderId();
            accountId = a.accountId();
            symbol = a.symbol();
            side = a.side();
            rests = a.orderType() == OrderType.LIMIT;
            price = a.price();
            quantity = a.quantity();
            leaves = a.quantity();
        }
    }

    private record LevelKey(String symbol, Side side, long price) {}

    private static final Comparator<LevelKey> KEY_ORDER =
            Comparator.comparing(LevelKey::symbol).thenComparing(LevelKey::side).thenComparingLong(LevelKey::price);

    private final Map<Long, Tracked> orders = new HashMap<>(); // lookups only, never iterated for output
    private final Map<Long, TreeSet<Long>> openByAccount = new HashMap<>(); // order ids in id order
    private final Map<String, NavigableMap<Long, long[]>> bids = new TreeMap<>();
    private final Map<String, NavigableMap<Long, long[]>> asks = new TreeMap<>();
    /** Levels touched by the current command, with their value before it: {quantity, count}. */
    private final Map<LevelKey, long[]> before = new HashMap<>();

    private SessionState session = SessionState.CLOSED;
    private long lastEventSeq;

    /** Applies one event. */
    public void onEvent(ExchangeEvent event) {
        lastEventSeq = event.seq();
        switch (event) {
            case OrderAccepted a -> {
                Tracked order = new Tracked(a);
                orders.put(order.orderId, order);
                openByAccount
                        .computeIfAbsent(order.accountId, k -> new TreeSet<>())
                        .add(order.orderId);
                contribute(order, +1);
            }
            case Trade t -> {
                reduce(t.buyOrderId(), t.quantity());
                reduce(t.sellOrderId(), t.quantity());
            }
            case OrderCancelled c -> reduce(c.orderId(), c.cancelledQuantity());
            case OrderModified m -> {
                Tracked order = orders.get(m.orderId());
                if (order != null) {
                    contribute(order, -1);
                    order.price = m.price();
                    order.quantity = m.quantity();
                    order.leaves = m.leavesQuantity();
                    contribute(order, +1);
                }
            }
            case SessionStateChanged s -> session = s.state();
            case OrderRejected r -> {
                // changes nothing on the book
            }
            case BookUpdate b -> {
                // not emitted by the engine; derived here instead
            }
        }
    }

    /** Ends the current command: returns the levels it changed, in symbol, side, price order. */
    public List<LevelChange> endCommand() {
        if (before.isEmpty()) {
            return List.of();
        }
        List<LevelKey> keys = new ArrayList<>(before.keySet());
        keys.sort(KEY_ORDER);
        List<LevelChange> changes = new ArrayList<>();
        for (LevelKey key : keys) {
            long[] was = before.get(key);
            long[] now =
                    side(key.side()).getOrDefault(key.symbol(), new TreeMap<>()).get(key.price());
            long quantity = now == null ? 0 : now[0];
            long count = now == null ? 0 : now[1];
            if (quantity != was[0] || count != was[1]) {
                changes.add(new LevelChange(key.symbol(), key.side(), key.price(), quantity, (int) count));
            }
        }
        before.clear();
        return changes;
    }

    /** Best-first levels of one side: bids high to low, asks low to high. */
    public List<DepthLevel> depth(String symbol, Side side, int maxLevels) {
        NavigableMap<Long, long[]> levels = side(side).get(symbol);
        List<DepthLevel> out = new ArrayList<>();
        if (levels == null) {
            return out;
        }
        for (Map.Entry<Long, long[]> e : levels.entrySet()) {
            if (out.size() == maxLevels) {
                break;
            }
            out.add(new DepthLevel(e.getKey(), e.getValue()[0], (int) e.getValue()[1]));
        }
        return out;
    }

    /** The account's orders with quantity still open, oldest first. */
    public List<OpenOrder> openOrders(long accountId) {
        TreeSet<Long> ids = openByAccount.get(accountId);
        List<OpenOrder> out = new ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (long id : ids) {
            Tracked o = orders.get(id);
            if (o.rests) {
                out.add(new OpenOrder(
                        o.orderId, o.clientOrderId, o.accountId, o.symbol, o.side, o.price, o.quantity, o.leaves));
            }
        }
        return out;
    }

    /** One open order, or null. */
    public OpenOrder openOrder(long orderId) {
        Tracked o = orders.get(orderId);
        return o == null || !o.rests
                ? null
                : new OpenOrder(
                        o.orderId, o.clientOrderId, o.accountId, o.symbol, o.side, o.price, o.quantity, o.leaves);
    }

    public SessionState session() {
        return session;
    }

    public long lastEventSeq() {
        return lastEventSeq;
    }

    private void reduce(long orderId, long quantity) {
        Tracked order = orders.get(orderId);
        if (order == null) {
            return;
        }
        contribute(order, -1);
        order.leaves -= quantity;
        if (order.leaves <= 0) {
            orders.remove(orderId);
            TreeSet<Long> ids = openByAccount.get(order.accountId);
            ids.remove(orderId);
            if (ids.isEmpty()) {
                openByAccount.remove(order.accountId);
            }
            return;
        }
        contribute(order, +1);
    }

    // Adds (+1) or removes (-1) one order's open quantity at its price level.
    private void contribute(Tracked order, int sign) {
        if (!order.rests || order.leaves <= 0) {
            return;
        }
        NavigableMap<Long, long[]> levels = side(order.side)
                .computeIfAbsent(
                        order.symbol,
                        k -> order.side == Side.BUY ? new TreeMap<>(Comparator.reverseOrder()) : new TreeMap<>());
        LevelKey key = new LevelKey(order.symbol, order.side, order.price);
        long[] level = levels.get(order.price);
        before.computeIfAbsent(key, k -> level == null ? new long[2] : level.clone());
        long[] next = level == null ? new long[2] : level;
        next[0] += sign * order.leaves;
        next[1] += sign;
        if (next[1] == 0) {
            levels.remove(order.price);
        } else {
            levels.put(order.price, next);
        }
    }

    private Map<String, NavigableMap<Long, long[]>> side(Side side) {
        return Objects.requireNonNull(side) == Side.BUY ? bids : asks;
    }
}
