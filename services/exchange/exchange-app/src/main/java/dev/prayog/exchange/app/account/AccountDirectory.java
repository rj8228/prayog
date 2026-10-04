package dev.prayog.exchange.app.account;

import dev.prayog.exchange.app.security.Trader;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Who is behind each account id, learned from authenticated requests. Account ids are hashes (ADR 0009), so this is
 * the only place that can say "account 3680... is trader1's main account". Kept in memory: after a restart an account
 * reappears here with its first request. The admin console reads it.
 */
public final class AccountDirectory {

    /** One account as the admin console shows it. Times are wall-clock epoch millis. */
    public record Entry(
            long accountId,
            String username,
            String label,
            String clientId,
            List<String> roles,
            long firstSeen,
            long lastSeen,
            long requests,
            long rejected) {}

    private static final class Live {
        final Trader trader;
        final long firstSeen;
        volatile long lastSeen;
        final AtomicLong requests = new AtomicLong();
        final AtomicLong rejected = new AtomicLong();

        Live(Trader trader, long now) {
            this.trader = trader;
            this.firstSeen = now;
            this.lastSeen = now;
        }
    }

    private final Map<Long, Live> accounts = new ConcurrentHashMap<>();

    /** Records a request from {@code trader}. */
    public void seen(Trader trader) {
        long now = System.currentTimeMillis();
        Live live = accounts.computeIfAbsent(trader.accountId(), id -> new Live(trader, now));
        live.lastSeen = now;
        live.requests.incrementAndGet();
    }

    public void rejected(Trader trader) {
        Live live = accounts.get(trader.accountId());
        if (live != null) {
            live.rejected.incrementAndGet();
        }
    }

    /** Most recently active first. */
    public List<Entry> entries() {
        return accounts.values().stream()
                .map(l -> new Entry(
                        l.trader.accountId(),
                        l.trader.username(),
                        l.trader.label(),
                        l.trader.clientId(),
                        l.trader.roles().stream().sorted().toList(),
                        l.firstSeen,
                        l.lastSeen,
                        l.requests.get(),
                        l.rejected.get()))
                .sorted(Comparator.comparingLong(Entry::lastSeen).reversed())
                .toList();
    }
}
