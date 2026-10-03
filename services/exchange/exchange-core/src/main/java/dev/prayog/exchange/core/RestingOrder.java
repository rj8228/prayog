package dev.prayog.exchange.core;

import dev.prayog.contracts.Side;

/**
 * An order sitting on the book. It is also a node in its price level's doubly linked list, so removing it from the
 * middle of the queue (a cancel, in S5) is O(1) without searching.
 */
final class RestingOrder {

    final long orderId;
    final long accountId;
    final Side side;
    final long price;
    /** Total ordered, including what has filled (changed by modify). */
    long quantity;
    /** Still open on the book. {@code quantity - leavesQuantity} is the filled amount. */
    long leavesQuantity;

    PriceLevel level;
    RestingOrder prev;
    RestingOrder next;

    RestingOrder(long orderId, long accountId, Side side, long price, long quantity, long leavesQuantity) {
        this.orderId = orderId;
        this.accountId = accountId;
        this.side = side;
        this.price = price;
        this.quantity = quantity;
        this.leavesQuantity = leavesQuantity;
    }
}
