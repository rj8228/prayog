package dev.prayog.exchange.core;

import java.util.function.Consumer;

/**
 * A hash map from {@code long} keys to objects, with no boxing and no entry objects: the order book's index of resting
 * orders by ID. A {@code HashMap<Long, V>} allocates a {@code Long} and a node on every put and a {@code Long} on every
 * lookup, which JFR showed to be about a third of the matching thread's allocation (docs/benchmarks.md).
 *
 * <p>Open addressing with linear probing over two parallel arrays; capacity is a power of two, at most half full. A
 * removal shifts later entries of the same probe chain back (Knuth's Algorithm R), so there are no tombstones and
 * lookups never slow down with churn. Key 0 marks an empty slot, so 0 is not a valid key (order IDs start at 1).
 * Iteration order is unspecified: callers that need an order sort (see {@code OrderBook.ordersInIdOrder}).
 *
 * <p>Not thread-safe: owned by the matching thread, like the rest of the book.
 */
final class LongObjectMap<V> {

    private long[] keys;
    private V[] values;
    private int mask;
    private int size;

    LongObjectMap(int expectedSize) {
        allocate(capacityFor(Math.max(expectedSize, 2)));
    }

    int size() {
        return size;
    }

    V get(long key) {
        if (key == 0) {
            return null;
        }
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                return values[i];
            }
            if (k == 0) {
                return null;
            }
        }
    }

    /** Maps {@code key} to {@code value}; returns the previous value, or null. */
    V put(long key, V value) {
        if (key == 0) {
            throw new IllegalArgumentException("0 is not a valid key");
        }
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == key) {
                V old = values[i];
                values[i] = value;
                return old;
            }
            if (k == 0) {
                keys[i] = key;
                values[i] = value;
                if (++size > (mask + 1) / 2) {
                    grow();
                }
                return null;
            }
        }
    }

    /** Removes {@code key}; returns its value, or null if it was absent. */
    V remove(long key) {
        if (key == 0) {
            return null;
        }
        for (int i = slot(key); ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == 0) {
                return null;
            }
            if (k == key) {
                V old = values[i];
                shiftBack(i);
                size--;
                return old;
            }
        }
    }

    void forEachValue(Consumer<? super V> action) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i] != 0) {
                action.accept(values[i]);
            }
        }
    }

    /** Empties slot {@code gap}, moving back any later entry of the chain that could no longer be found. */
    private void shiftBack(int gap) {
        for (int i = (gap + 1) & mask; ; i = (i + 1) & mask) {
            long k = keys[i];
            if (k == 0) {
                break;
            }
            int home = slot(k);
            // The entry at i may move into the gap only if its home slot is not inside (gap, i], cyclically.
            boolean homeBetween = gap <= i ? (gap < home && home <= i) : (gap < home || home <= i);
            if (!homeBetween) {
                keys[gap] = k;
                values[gap] = values[i];
                gap = i;
            }
        }
        keys[gap] = 0;
        values[gap] = null;
    }

    private int slot(long key) {
        // Order IDs are sequential; a multiplicative mix spreads them over the table (Fibonacci hashing).
        return (int) ((key * 0x9E3779B97F4A7C15L) >>> 32) & mask;
    }

    private void grow() {
        long[] oldKeys = keys;
        V[] oldValues = values;
        allocate(oldKeys.length * 2);
        size = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != 0) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void allocate(int capacity) {
        keys = new long[capacity];
        values = (V[]) new Object[capacity];
        mask = capacity - 1;
    }

    private static int capacityFor(int expectedSize) {
        int capacity = Integer.highestOneBit(expectedSize * 2 - 1) << 1;
        return Math.max(capacity, 4);
    }

    @Override
    public String toString() {
        return "LongObjectMap[size=" + size + ", capacity=" + keys.length + "]";
    }
}
