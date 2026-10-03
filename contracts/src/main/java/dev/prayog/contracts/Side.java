package dev.prayog.contracts;

/** Which side of the book an order is on. */
public enum Side {
    BUY,
    SELL;

    /** The side this order trades against. */
    public Side opposite() {
        return this == BUY ? SELL : BUY;
    }
}
