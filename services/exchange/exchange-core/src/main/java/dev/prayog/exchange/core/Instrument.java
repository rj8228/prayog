package dev.prayog.exchange.core;

import java.util.Objects;

/**
 * Static facts about a tradable symbol.
 *
 * @param tickSize smallest price step in paise; every limit price must be a multiple of it
 * @param maxOrderQuantity largest quantity one order may carry (like an exchange's freeze quantity); it also keeps
 *     level totals far from {@code long} overflow
 * @param referencePrice price the band is measured from, in paise (normally the previous close; static in the MVP)
 * @param bandPercent how far from the reference price an order may be priced today, in whole percent (NSE uses 2, 5,
 *     10 or 20)
 */
public record Instrument(String symbol, long tickSize, long maxOrderQuantity, long referencePrice, int bandPercent) {

    public Instrument {
        Objects.requireNonNull(symbol, "symbol");
        if (tickSize <= 0) {
            throw new IllegalArgumentException("tickSize must be positive: " + tickSize);
        }
        if (maxOrderQuantity <= 0) {
            throw new IllegalArgumentException("maxOrderQuantity must be positive: " + maxOrderQuantity);
        }
        if (referencePrice <= 0) {
            throw new IllegalArgumentException("referencePrice must be positive: " + referencePrice);
        }
        if (bandPercent < 1 || bandPercent > 99) {
            throw new IllegalArgumentException("bandPercent must be 1..99: " + bandPercent);
        }
        if (bandLow(referencePrice, bandPercent, tickSize) > bandHigh(referencePrice, bandPercent, tickSize)) {
            throw new IllegalArgumentException("band contains no valid price for " + symbol);
        }
    }

    /** Lowest valid price today: reference − band, rounded <em>up</em> to the tick so it stays inside the band. */
    public long bandLow() {
        return bandLow(referencePrice, bandPercent, tickSize);
    }

    /** Highest valid price today: reference + band, rounded <em>down</em> to the tick. */
    public long bandHigh() {
        return bandHigh(referencePrice, bandPercent, tickSize);
    }

    // Integer arithmetic only: ceil(a / b) for positive a, b is (a + b - 1) / b.
    private static long bandLow(long reference, int percent, long tick) {
        long paise = ceilDiv(reference * (100 - percent), 100);
        return Math.max(tick, ceilDiv(paise, tick) * tick);
    }

    private static long bandHigh(long reference, int percent, long tick) {
        long paise = reference * (100 + percent) / 100;
        return paise / tick * tick;
    }

    private static long ceilDiv(long a, long b) {
        return (a + b - 1) / b;
    }
}
