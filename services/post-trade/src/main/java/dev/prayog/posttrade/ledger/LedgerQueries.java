package dev.prayog.posttrade.ledger;

import static dev.prayog.posttrade.db.Tables.CONSUMER_PROGRESS;
import static dev.prayog.posttrade.db.Tables.FILLS;
import static dev.prayog.posttrade.db.Tables.MARKS;
import static dev.prayog.posttrade.db.Tables.ORDERS;
import static dev.prayog.posttrade.db.Tables.POSITIONS;
import static dev.prayog.posttrade.db.Tables.REJECTIONS;
import static dev.prayog.posttrade.db.Tables.TRADES;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;

/** Read side of the ledger: what the API, the leaderboard and the checks ask. Amounts in paise. */
public class LedgerQueries {

    /** One open (or closed today) position, marked at the last trade price. */
    public record PositionView(
            String symbol,
            long quantity,
            long averagePrice,
            long cost,
            long mark,
            long realisedPnl,
            long unrealisedPnl,
            long charges,
            long netPnl,
            long bought,
            long sold,
            long trades) {}

    /** An account's totals: net = realised + unrealised - charges. */
    public record Pnl(long accountId, long realisedPnl, long unrealisedPnl, long charges, long netPnl, long trades) {}

    public record FillView(
            long tradeId,
            long orderId,
            String symbol,
            String side,
            long price,
            long quantity,
            long charges,
            long realisedPnl,
            long simTime,
            long eventId) {}

    public record OrderView(
            long orderId,
            String clientOrderId,
            String symbol,
            String side,
            String orderType,
            long price,
            long quantity,
            long filledQuantity,
            long leavesQuantity,
            String status,
            String reason,
            long createdAt,
            long updatedAt) {}

    public record RejectionView(long eventId, String clientOrderId, String symbol, String reason, long simTime) {}

    /**
     * Whole-market totals. A market is zero-sum: every share bought was sold by someone, and every rupee paid was
     * received by someone. So at one mark per symbol, the quantities and the P&L before charges add up to exactly
     * zero across all accounts. A non-zero total means the ledger lost or double-counted something.
     */
    public record Totals(
            long trades,
            long accounts,
            long pnlBeforeCharges,
            long charges,
            Map<String, Long> netQuantityBySymbol,
            Map<Integer, Long> lastEventIdByPartition,
            long eventsConsumed) {}

    private final DSLContext db;

    public LedgerQueries(DSLContext db) {
        this.db = db;
    }

    private static final Field<Long> MARK = DSL.coalesce(MARKS.PRICE, DSL.val(0L));

    public List<PositionView> positions(long accountId) {
        return db.select(POSITIONS.fields())
                .select(MARK.as("mark"))
                .from(POSITIONS)
                .leftJoin(MARKS)
                .on(MARKS.SYMBOL.eq(POSITIONS.SYMBOL))
                .where(POSITIONS.ACCOUNT_ID.eq(accountId))
                .orderBy(POSITIONS.SYMBOL)
                .fetch(r -> {
                    long quantity = r.get(POSITIONS.QUANTITY);
                    long cost = r.get(POSITIONS.COST);
                    long mark = r.get("mark", Long.class);
                    long unrealised = quantity == 0
                            ? 0
                            : PositionMath.unrealised(new PositionMath.Position(quantity, cost), mark);
                    long realised = r.get(POSITIONS.REALISED_PNL);
                    long charges = r.get(POSITIONS.CHARGES);
                    return new PositionView(
                            r.get(POSITIONS.SYMBOL),
                            quantity,
                            quantity == 0 ? 0 : Math.round((double) cost / quantity), // display only
                            cost,
                            mark,
                            realised,
                            unrealised,
                            charges,
                            realised + unrealised - charges,
                            r.get(POSITIONS.BOUGHT),
                            r.get(POSITIONS.SOLD),
                            r.get(POSITIONS.TRADES));
                });
    }

    public Pnl pnl(long accountId) {
        Map<Long, Pnl> one = pnl(List.of(accountId));
        return one.getOrDefault(accountId, new Pnl(accountId, 0, 0, 0, 0, 0));
    }

    /** Totals for {@code accounts} (all accounts with a position row when null). */
    public Map<Long, Pnl> pnl(Collection<Long> accounts) {
        Field<BigDecimal> realised = DSL.sum(POSITIONS.REALISED_PNL);
        Field<BigDecimal> unrealised = DSL.sum(POSITIONS.QUANTITY.mul(MARK).minus(POSITIONS.COST));
        Field<BigDecimal> charges = DSL.sum(POSITIONS.CHARGES);
        Field<BigDecimal> trades = DSL.sum(POSITIONS.TRADES);
        var query = db.select(POSITIONS.ACCOUNT_ID, realised, unrealised, charges, trades)
                .from(POSITIONS)
                .leftJoin(MARKS)
                .on(MARKS.SYMBOL.eq(POSITIONS.SYMBOL));
        var filtered = accounts == null ? query : query.where(POSITIONS.ACCOUNT_ID.in(accounts));
        Map<Long, Pnl> out = new LinkedHashMap<>();
        filtered.groupBy(POSITIONS.ACCOUNT_ID).forEach(r -> {
            long r1 = r.get(realised).longValueExact();
            long u = r.get(unrealised).longValueExact();
            long c = r.get(charges).longValueExact();
            long accountId = r.get(POSITIONS.ACCOUNT_ID);
            out.put(
                    accountId,
                    new Pnl(accountId, r1, u, c, r1 + u - c, r.get(trades).longValueExact()));
        });
        return out;
    }

    /** Accounts holding a non-zero position in any of {@code symbols}: their unrealised P&L moved with the mark. */
    public List<Long> holders(Collection<String> symbols) {
        if (symbols.isEmpty()) {
            return List.of();
        }
        return db.selectDistinct(POSITIONS.ACCOUNT_ID)
                .from(POSITIONS)
                .where(POSITIONS.SYMBOL.in(symbols).and(POSITIONS.QUANTITY.ne(0L)))
                .fetch(POSITIONS.ACCOUNT_ID);
    }

    public List<FillView> fills(long accountId, int limit) {
        return db.selectFrom(FILLS)
                .where(FILLS.ACCOUNT_ID.eq(accountId))
                .orderBy(FILLS.EVENT_ID.desc())
                .limit(limit)
                .fetch(r -> new FillView(
                        r.getTradeId(),
                        r.getOrderId(),
                        r.getSymbol(),
                        r.getSide(),
                        r.getPrice(),
                        r.getQuantity(),
                        r.getCharges(),
                        r.getRealisedPnl(),
                        r.getSimTime(),
                        r.getEventId()));
    }

    public List<OrderView> orders(long accountId, int limit) {
        return db.selectFrom(ORDERS)
                .where(ORDERS.ACCOUNT_ID.eq(accountId))
                .orderBy(ORDERS.ORDER_ID.desc())
                .limit(limit)
                .fetch(r -> new OrderView(
                        r.getOrderId(),
                        r.getClientOrderId(),
                        r.getSymbol(),
                        r.getSide(),
                        r.getOrderType(),
                        r.getPrice(),
                        r.getQuantity(),
                        r.getFilledQuantity(),
                        r.getLeavesQuantity(),
                        r.getStatus(),
                        r.getReason(),
                        r.getCreatedAt(),
                        r.getUpdatedAt()));
    }

    public List<RejectionView> rejections(long accountId, int limit) {
        return db.selectFrom(REJECTIONS)
                .where(REJECTIONS.ACCOUNT_ID.eq(accountId))
                .orderBy(REJECTIONS.EVENT_ID.desc())
                .limit(limit)
                .fetch(r -> new RejectionView(
                        r.getEventId(), r.getClientOrderId(), r.getSymbol(), r.getReason(), r.getSimTime()));
    }

    public Totals totals() {
        long trades = db.fetchCount(TRADES);
        long accounts = db.fetchCount(db.selectDistinct(POSITIONS.ACCOUNT_ID).from(POSITIONS));
        BigDecimal pnl = db.select(DSL.sum(POSITIONS
                        .REALISED_PNL
                        .plus(POSITIONS.QUANTITY.mul(MARK))
                        .minus(POSITIONS.COST)))
                .from(POSITIONS)
                .leftJoin(MARKS)
                .on(MARKS.SYMBOL.eq(POSITIONS.SYMBOL))
                .fetchOne(0, BigDecimal.class);
        BigDecimal charges =
                db.select(DSL.sum(POSITIONS.CHARGES)).from(POSITIONS).fetchOne(0, BigDecimal.class);
        Map<String, Long> bySymbol = new LinkedHashMap<>();
        db.select(POSITIONS.SYMBOL, DSL.sum(POSITIONS.QUANTITY).cast(SQLDataType.BIGINT))
                .from(POSITIONS)
                .groupBy(POSITIONS.SYMBOL)
                .orderBy(POSITIONS.SYMBOL)
                .forEach(r -> bySymbol.put(r.value1(), r.value2()));
        Map<Integer, Long> partitions = new LinkedHashMap<>();
        long[] consumed = {0};
        db.selectFrom(CONSUMER_PROGRESS)
                .orderBy(CONSUMER_PROGRESS.TOPIC_PARTITION)
                .forEach(r -> {
                    partitions.put(r.getTopicPartition(), r.getLastEventId());
                    consumed[0] += r.getEvents();
                });
        return new Totals(
                trades,
                accounts,
                pnl == null ? 0 : pnl.longValueExact(),
                charges == null ? 0 : charges.longValueExact(),
                bySymbol,
                partitions,
                consumed[0]);
    }
}
