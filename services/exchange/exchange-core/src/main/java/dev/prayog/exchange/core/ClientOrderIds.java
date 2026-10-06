package dev.prayog.exchange.core;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The client order IDs each account has used today, so a repeated one can be refused (ADR 0014).
 *
 * <p>Why: a client that times out waiting for an answer cannot know whether its order arrived. Retrying with the
 * <i>same</i> client order ID is then safe: if the first attempt got through, the retry is rejected as
 * {@code DUPLICATE_CLIENT_ORDER_ID} instead of creating a second order. This is the same rule as FIX's ClOrdID.
 *
 * <p>Scope: per account, for the trading day (cleared when the session closes, when every order expires), and only IDs
 * of <i>accepted</i> orders, so a request that was rejected (say, outside the band) may be corrected and sent again
 * under the same ID. Memory is bounded: each account remembers its most recent {@link #PER_ACCOUNT} IDs; a retry is
 * caught as long as fewer orders than that came from the same account in between.
 *
 * <p>Deterministic: lookups only, never iteration over a hash set, and eviction in arrival order.
 */
final class ClientOrderIds {

    /** IDs remembered per account per day. A retry normally follows within seconds; this covers far more. */
    static final int PER_ACCOUNT = 10_000;

    private static final class Window {
        final ArrayDeque<String> order = new ArrayDeque<>();
        final Set<String> ids = new HashSet<>();
    }

    private final Map<Long, Window> byAccount = new HashMap<>();
    private final int perAccount;

    ClientOrderIds() {
        this(PER_ACCOUNT);
    }

    ClientOrderIds(int perAccount) {
        this.perAccount = perAccount;
    }

    boolean contains(long accountId, String clientOrderId) {
        Window window = byAccount.get(accountId);
        return window != null && window.ids.contains(clientOrderId);
    }

    void add(long accountId, String clientOrderId) {
        Window window = byAccount.computeIfAbsent(accountId, id -> new Window());
        if (window.ids.add(clientOrderId)) {
            window.order.addLast(clientOrderId);
            if (window.order.size() > perAccount) {
                window.ids.remove(window.order.removeFirst());
            }
        }
    }

    /** A new trading day: every ID may be used again. */
    void clear() {
        byAccount.clear();
    }
}
