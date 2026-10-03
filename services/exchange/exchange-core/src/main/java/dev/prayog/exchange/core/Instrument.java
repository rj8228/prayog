package dev.prayog.exchange.core;

import java.util.Objects;

/**
 * Static facts about a tradable symbol.
 *
 * @param tickSize smallest price step in paise; every limit price must be a multiple of it
 * @param maxOrderQuantity largest quantity one order may carry (like an exchange's freeze quantity); it also keeps
 *     level totals far from {@code long} overflow
 */
public record Instrument(String symbol, long tickSize, long maxOrderQuantity) {

    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive: " + tickSize);
        }
        if (maxOrderQuantity <= 0) {
            throw new IllegalArgumentException("maxOrderQuantity must be positive: " + maxOrderQuantity);
        }
    }
}
