package dev.prayog.exchange.app.security;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Token bucket per account (BUILD_PLAN 16.2 #22): each account may send {@code rate} orders per second on average,
 * with bursts up to {@code burst}. Checked in the gateway before anything reaches the ring, so a flood never costs the
 * exchange a journal write.
 */
public final class RateLimiter {

    private final LongSupplier nanos;
    private final Map<Long, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimiter(LongSupplier nanos) {
        this.nanos = nanos;
    }

    private static final class Bucket {
        double tokens;
        long lastNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastNanos = now;
        }
    }

    /** Takes one token for {@code accountId}; false means "too many requests, slow down". */
    public boolean tryAcquire(long accountId, int ratePerSecond, int burst) {
        long now = nanos.getAsLong();
        Bucket bucket = buckets.computeIfAbsent(accountId, id -> new Bucket(burst, now));
        synchronized (bucket) {
            double refill = (now - bucket.lastNanos) / 1e9 * ratePerSecond;
            bucket.tokens = Math.min(burst, bucket.tokens + refill);
            bucket.lastNanos = now;
            if (bucket.tokens < 1) {
                return false;
            }
            bucket.tokens -= 1;
            return true;
        }
    }
}
