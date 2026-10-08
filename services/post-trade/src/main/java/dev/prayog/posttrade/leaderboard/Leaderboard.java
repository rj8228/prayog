package dev.prayog.posttrade.leaderboard;

import static dev.prayog.posttrade.db.Tables.ACCOUNT_NAMES;

import dev.prayog.posttrade.ledger.LedgerQueries;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * The leaderboard (S17): a Redis sorted set {@code prayog:leaderboard}, member = account id, score = net P&L in paise
 * (realised + unrealised - charges). Sorted sets keep members ordered by score, so the top N and an account's rank
 * are O(log n) reads, whatever the number of accounts.
 *
 * <p>PostgreSQL is the source of truth; Redis is a fast, rebuildable view. Scores are doubles, exact for whole numbers
 * up to 2^53 paise (about 90 trillion rupees). If Redis loses its data or a write fails, {@link #rebuild} recomputes
 * every account from the ledger.
 */
public class Leaderboard {

    public static final String KEY = "prayog:leaderboard";

    /** One row of the board. {@code name} is "username/label" when known, else null. */
    public record Entry(long rank, long accountId, String name, long netPnl) {}

    private final StringRedisTemplate redis;
    private final LedgerQueries ledger;
    private final DSLContext db;
    private final Set<Long> named = ConcurrentHashMap.newKeySet();

    public Leaderboard(StringRedisTemplate redis, LedgerQueries ledger, DSLContext db) {
        this.redis = redis;
        this.ledger = ledger;
        this.db = db;
    }

    /** Recomputes and stores the score of each of {@code accounts}. */
    public void update(Collection<Long> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        Map<Long, LedgerQueries.Pnl> pnl = ledger.pnl(accounts);
        Set<ZSetOperations.TypedTuple<String>> scores = new HashSet<>();
        pnl.forEach((id, p) -> scores.add(ZSetOperations.TypedTuple.of(Long.toString(id), (double) p.netPnl())));
        if (!scores.isEmpty()) {
            redis.opsForZSet().add(KEY, scores);
        }
    }

    /** Replaces the whole board with fresh scores from the ledger (start-up, after a Redis failure). */
    public void rebuild() {
        Map<Long, LedgerQueries.Pnl> pnl = ledger.pnl(null);
        String temp = KEY + ":rebuild";
        redis.delete(temp);
        Set<ZSetOperations.TypedTuple<String>> scores = new HashSet<>();
        pnl.forEach((id, p) -> scores.add(ZSetOperations.TypedTuple.of(Long.toString(id), (double) p.netPnl())));
        if (scores.isEmpty()) {
            redis.delete(KEY);
            return;
        }
        redis.opsForZSet().add(temp, scores);
        redis.rename(temp, KEY); // atomic swap: readers see the old board or the new one, never half
    }

    public List<Entry> top(int limit) {
        Set<ZSetOperations.TypedTuple<String>> rows = redis.opsForZSet().reverseRangeWithScores(KEY, 0, limit - 1);
        List<Entry> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        List<Long> ids = rows.stream().map(t -> Long.parseLong(t.getValue())).toList();
        Map<Long, String> names = names(ids);
        long rank = 1;
        for (ZSetOperations.TypedTuple<String> row : rows) {
            long id = Long.parseLong(row.getValue());
            out.add(new Entry(rank++, id, names.get(id), Math.round(row.getScore())));
        }
        return out;
    }

    /** 1-based rank of {@code accountId}, or 0 if it has not traded. */
    public long rank(long accountId) {
        Long r = redis.opsForZSet().reverseRank(KEY, Long.toString(accountId));
        return r == null ? 0 : r + 1;
    }

    public long size() {
        Long n = redis.opsForZSet().zCard(KEY);
        return n == null ? 0 : n;
    }

    /**
     * Remembers who is behind an account (once per account per process). Keycloak names a client's own account
     * {@code service-account-<client>}; the board shows just the client.
     */
    public void name(long accountId, String rawUsername, String label) {
        String username = rawUsername.startsWith("service-account-")
                ? rawUsername.substring("service-account-".length())
                : rawUsername;
        if (!named.contains(accountId)) {
            db.insertInto(ACCOUNT_NAMES)
                    .set(ACCOUNT_NAMES.ACCOUNT_ID, accountId)
                    .set(ACCOUNT_NAMES.USERNAME, truncate(username, 64))
                    .set(ACCOUNT_NAMES.LABEL, truncate(label, 32))
                    .onConflict(ACCOUNT_NAMES.ACCOUNT_ID)
                    .doUpdate()
                    .set(ACCOUNT_NAMES.USERNAME, truncate(username, 64))
                    .set(ACCOUNT_NAMES.LABEL, truncate(label, 32))
                    .execute();
            // Remembered only once written: a failed write (database restarting) is retried on the next request.
            // Two requests racing here both upsert the same row, which is harmless.
            named.add(accountId);
        }
    }

    private Map<Long, String> names(List<Long> ids) {
        return db.selectFrom(ACCOUNT_NAMES)
                .where(ACCOUNT_NAMES.ACCOUNT_ID.in(ids))
                .fetchMap(ACCOUNT_NAMES.ACCOUNT_ID, r -> r.getUsername() + "/" + r.getLabel());
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
