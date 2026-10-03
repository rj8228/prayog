package dev.prayog.contracts;

/** LIMIT orders carry a price and may rest on the book; MARKET orders take what is there and never rest. */
public enum OrderType {
    LIMIT,
    MARKET
}
