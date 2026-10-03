package dev.prayog.exchange.core;

import dev.prayog.contracts.Side;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * Resting orders for one symbol. A data structure only: matching rules live in {@link MatchingEngine}.
 *
 * <p>Each side is a sorted map from price to {@link PriceLevel}, ordered so the best price comes first (highest bid,
 * lowest ask). Each level is a FIFO queue. An index by order ID finds any order in O(1).
 *
 * <p>Not thread-safe by design: only the matching thread touches it, so it needs no locks.
 */
public final class OrderBook {

    private final String symbol;
    private final NavigableMap<Long, PriceLevel> bids = new TreeMap<>(Collections.reverseOrder());
    private final NavigableMap<Long, PriceLevel> asks = new TreeMap<>();
    private final Map<Long, RestingOrder> ordersById = new HashMap<>();

    OrderBook(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** Highest bid price, or 0 when there are no bids. */
    public long bestBid() {
        return bestPrice(bids);
    }

    /** Lowest ask price, or 0 when there are no asks. */
    public long bestAsk() {
        return bestPrice(asks);
    }

    /** Open quantity of a resting order, or 0 if it is not on the book. */
    public long leavesQuantity(long orderId) {
        RestingOrder order = ordersById.get(orderId);
        return order == null ? 0 : order.leavesQuantity;
    }

    public int orderCount() {
        return ordersById.size();
    }

    /** Up to {@code maxLevels} levels of one side, best price first. */
    public List<DepthLevel> depth(Side side, int maxLevels) {
        List<DepthLevel> result = new ArrayList<>();
        for (PriceLevel level : levels(side).values()) {
            if (result.size() == maxLevels) {
                break;
            }
            result.add(new DepthLevel(level.price, level.totalQuantity(), level.orderCount()));
        }
        return result;
    }

    /** The resting order with this ID, or null. */
    RestingOrder order(long orderId) {
        return ordersById.get(orderId);
    }

    /**
     * Every resting order, lowest order ID first. Sorts, so it is for rare bulk operations (end-of-day expiry, the
     * kill switch), never the per-order path.
     */
    List<RestingOrder> ordersInIdOrder() {
        List<RestingOrder> orders = new ArrayList<>(ordersById.values());
        orders.sort(Comparator.comparingLong(o -> o.orderId));
        return orders;
    }

    /** The best level on a side, or null when that side is empty. */
    PriceLevel bestLevel(Side side) {
        Map.Entry<Long, PriceLevel> best = levels(side).firstEntry();
        return best == null ? null : best.getValue();
    }

    /** Puts an order at the back of the queue for its price, creating the level if needed. */
    void add(RestingOrder order) {
        levels(order.side).computeIfAbsent(order.price, PriceLevel::new).append(order);
        ordersById.put(order.orderId, order);
    }

    /** Fills part or all of a resting order; a fully filled order leaves the book. */
    void fill(RestingOrder order, long quantity) {
        order.level.reduce(order, quantity);
        if (order.leavesQuantity == 0) {
            remove(order);
        }
    }

    /** Lowers an order's open quantity in place, keeping its queue position. Must leave some quantity open. */
    void reduce(RestingOrder order, long quantity) {
        order.level.reduce(order, quantity);
    }

    /** Takes an order off the book, dropping its level if it was the last one there. */
    void remove(RestingOrder order) {
        PriceLevel level = order.level;
        level.remove(order);
        if (level.isEmpty()) {
            levels(order.side).remove(level.price);
        }
        ordersById.remove(order.orderId);
    }

    /**
     * Checks the book's internal consistency and throws if anything is off. For tests: walks every order, so it is
     * far too slow for the order path.
     */
    void checkInvariants() {
        int ordersSeen = 0;
        for (Side side : Side.values()) {
            for (Map.Entry<Long, PriceLevel> entry : levels(side).entrySet()) {
                PriceLevel level = entry.getValue();
                check(level.price == entry.getKey(), "level keyed by its own price");
                check(!level.isEmpty(), "no empty levels");
                long quantity = 0;
                int count = 0;
                RestingOrder prev = null;
                for (RestingOrder o = level.head(); o != null; o = o.next) {
                    check(o.prev == prev, "back links match forward links");
                    check(o.level == level && o.side == side && o.price == level.price, "order in the right level");
                    check(o.leavesQuantity > 0, "resting orders have open quantity");
                    check(o.quantity >= o.leavesQuantity, "open never exceeds ordered");
                    check(ordersById.get(o.orderId) == o, "order indexed by ID");
                    quantity += o.leavesQuantity;
                    count++;
                    prev = o;
                }
                check(quantity == level.totalQuantity() && count == level.orderCount(), "level totals match");
                ordersSeen += count;
            }
        }
        check(ordersSeen == ordersById.size(), "index holds exactly the resting orders");
        check(bids.isEmpty() || asks.isEmpty() || bestBid() < bestAsk(), "book not crossed");
    }

    private NavigableMap<Long, PriceLevel> levels(Side side) {
        return side == Side.BUY ? bids : asks;
    }

    private static long bestPrice(NavigableMap<Long, PriceLevel> side) {
        Map.Entry<Long, PriceLevel> best = side.firstEntry();
        return best == null ? 0 : best.getKey();
    }

    private static void check(boolean condition, String invariant) {
        if (!condition) {
            throw new IllegalStateException("Order book invariant broken: " + invariant);
        }
    }
}
