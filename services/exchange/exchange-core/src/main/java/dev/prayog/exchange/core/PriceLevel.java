package dev.prayog.exchange.core;

/**
 * All resting orders at one price on one side, oldest first (time priority). Keeps running totals so depth is
 * available without walking the queue.
 */
final class PriceLevel {

    final long price;
    private RestingOrder head;
    private RestingOrder tail;
    private long totalQuantity;
    private int orderCount;

    PriceLevel(long price) {
        this.price = price;
    }

    RestingOrder head() {
        return head;
    }

    long totalQuantity() {
        return totalQuantity;
    }

    int orderCount() {
        return orderCount;
    }

    boolean isEmpty() {
        return head == null;
    }

    /** Joins the back of the queue. */
    void append(RestingOrder order) {
        order.level = this;
        order.prev = tail;
        order.next = null;
        if (tail == null) {
            head = order;
        } else {
            tail.next = order;
        }
        tail = order;
        totalQuantity += order.leavesQuantity;
        orderCount++;
    }

    /** Unlinks the order from anywhere in the queue; its remaining quantity leaves the level total. */
    void remove(RestingOrder order) {
        if (order.prev == null) {
            head = order.next;
        } else {
            order.prev.next = order.next;
        }
        if (order.next == null) {
            tail = order.prev;
        } else {
            order.next.prev = order.prev;
        }
        totalQuantity -= order.leavesQuantity;
        orderCount--;
        order.level = null;
        order.prev = null;
        order.next = null;
    }

    /** Records a partial fill of an order in this level. */
    void reduce(RestingOrder order, long quantity) {
        order.leavesQuantity -= quantity;
        totalQuantity -= quantity;
    }
}
