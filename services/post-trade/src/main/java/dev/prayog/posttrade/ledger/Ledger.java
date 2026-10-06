package dev.prayog.posttrade.ledger;

import static dev.prayog.posttrade.db.Tables.CONSUMER_PROGRESS;
import static dev.prayog.posttrade.db.Tables.FILLS;
import static dev.prayog.posttrade.db.Tables.MARKS;
import static dev.prayog.posttrade.db.Tables.ORDERS;
import static dev.prayog.posttrade.db.Tables.POSITIONS;
import static dev.prayog.posttrade.db.Tables.REJECTIONS;
import static dev.prayog.posttrade.db.Tables.TRADES;

import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import dev.prayog.posttrade.db.tables.records.PositionsRecord;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies exchange events to the ledger tables. Every write is idempotent, because Kafka delivers at least once
 * (ADR 0013, 0015):
 *
 * <ul>
 *   <li><b>Trades</b> are inserted with their trade id as primary key. Only when the insert really adds a row are the
 *       fills, positions, charges and order fills applied, in the same transaction. A redelivered trade inserts
 *       nothing and changes nothing. This holds whatever order duplicates arrive in.
 *   <li><b>Order status</b> changes carry the event id and only apply over an older one ({@code last_event_id}).
 *   <li><b>Rejections</b> are keyed by event id.
 * </ul>
 *
 * <p>A whole Kafka batch is one transaction: either all of it is in the ledger or none, and Kafka offsets are
 * committed only after it.
 */
public class Ledger {

    /** One event from the topic, with the partition it came from. */
    public record Incoming(int partition, ExchangeEvent event) {}

    /** What a batch changed, for the leaderboard: accounts that traded and symbols whose mark moved. */
    public record Changes(Set<Long> accounts, Set<String> markedSymbols, int applied, int duplicates) {}

    private final DSLContext db;
    private final Charges charges;

    public Ledger(DSLContext db, Charges charges) {
        this.db = db;
        this.charges = charges;
    }

    @Transactional
    public Changes apply(List<Incoming> batch) {
        Set<Long> accounts = new TreeSet<>();
        Set<String> marked = new TreeSet<>();
        int applied = 0;
        int duplicates = 0;
        Map<Integer, long[]> progress = new TreeMap<>(); // partition -> {highest event id, count}
        for (Incoming in : batch) {
            boolean fresh = switch (in.event()) {
                case Trade t -> trade(t, accounts, marked);
                case OrderAccepted a -> accepted(a);
                case OrderCancelled c -> cancelled(c);
                case OrderModified m -> modified(m);
                case OrderRejected r -> rejected(r);
                case BookUpdate b -> true; // market data: not the ledger's concern
                case SessionStateChanged s -> true;
            };
            if (fresh) {
                applied++;
            } else {
                duplicates++;
            }
            long[] p = progress.computeIfAbsent(in.partition(), k -> new long[2]);
            p[0] = Math.max(p[0], in.event().seq());
            p[1]++;
        }
        progress.forEach((partition, p) -> progress(partition, p[0], p[1]));
        return new Changes(accounts, marked, applied, duplicates);
    }

    private boolean trade(Trade t, Set<Long> accounts, Set<String> marked) {
        int inserted = db.insertInto(TRADES)
                .set(TRADES.TRADE_ID, t.tradeId())
                .set(TRADES.EVENT_ID, t.seq())
                .set(TRADES.SIM_TIME, t.simTime())
                .set(TRADES.SYMBOL, t.symbol())
                .set(TRADES.PRICE, t.price())
                .set(TRADES.QUANTITY, t.quantity())
                .set(TRADES.AGGRESSOR, t.aggressorSide().name())
                .set(TRADES.BUY_ORDER_ID, t.buyOrderId())
                .set(TRADES.SELL_ORDER_ID, t.sellOrderId())
                .set(TRADES.BUY_ACCOUNT_ID, t.buyAccountId())
                .set(TRADES.SELL_ACCOUNT_ID, t.sellAccountId())
                .onConflictDoNothing()
                .execute();
        if (inserted == 0) {
            return false; // seen before: everything it changes is already in the ledger
        }
        side(t, Side.BUY, t.buyAccountId(), t.buyOrderId());
        side(t, Side.SELL, t.sellAccountId(), t.sellOrderId());
        accounts.add(t.buyAccountId());
        accounts.add(t.sellAccountId());
        int moved = db.insertInto(MARKS)
                .set(MARKS.SYMBOL, t.symbol())
                .set(MARKS.PRICE, t.price())
                .set(MARKS.EVENT_ID, t.seq())
                .set(MARKS.SIM_TIME, t.simTime())
                .onConflict(MARKS.SYMBOL)
                .doUpdate()
                .set(MARKS.PRICE, t.price())
                .set(MARKS.EVENT_ID, t.seq())
                .set(MARKS.SIM_TIME, t.simTime())
                .where(MARKS.EVENT_ID.lt(t.seq()))
                .execute();
        if (moved > 0) {
            marked.add(t.symbol());
        }
        return true;
    }

    // One side of a trade: position, charges, fill row, and the order's filled quantity.
    private void side(Trade t, Side side, long accountId, long orderId) {
        PositionsRecord row = db.selectFrom(POSITIONS)
                .where(POSITIONS.ACCOUNT_ID.eq(accountId).and(POSITIONS.SYMBOL.eq(t.symbol())))
                .forUpdate()
                .fetchOne();
        PositionMath.Position before =
                row == null ? PositionMath.FLAT : new PositionMath.Position(row.getQuantity(), row.getCost());
        PositionMath.Applied after = PositionMath.apply(before, side, t.quantity(), t.price());
        long value = Math.multiplyExact(t.quantity(), t.price());
        long fee = charges.on(value);
        if (row == null) {
            row = db.newRecord(POSITIONS);
            row.setAccountId(accountId);
            row.setSymbol(t.symbol());
            row.setRealisedPnl(0L);
            row.setCharges(0L);
            row.setBought(0L);
            row.setSold(0L);
            row.setTurnover(0L);
            row.setTrades(0L);
        }
        row.setQuantity(after.position().quantity());
        row.setCost(after.position().cost());
        row.setRealisedPnl(row.getRealisedPnl() + after.realised());
        row.setCharges(row.getCharges() + fee);
        if (side == Side.BUY) {
            row.setBought(row.getBought() + t.quantity());
        } else {
            row.setSold(row.getSold() + t.quantity());
        }
        row.setTurnover(row.getTurnover() + value);
        row.setTrades(row.getTrades() + 1);
        row.setUpdatedAt(t.simTime());
        row.store();

        db.insertInto(FILLS)
                .set(FILLS.TRADE_ID, t.tradeId())
                .set(FILLS.SIDE, side.name())
                .set(FILLS.ACCOUNT_ID, accountId)
                .set(FILLS.ORDER_ID, orderId)
                .set(FILLS.SYMBOL, t.symbol())
                .set(FILLS.PRICE, t.price())
                .set(FILLS.QUANTITY, t.quantity())
                .set(FILLS.CHARGES, fee)
                .set(FILLS.REALISED_PNL, after.realised())
                .set(FILLS.SIM_TIME, t.simTime())
                .set(FILLS.EVENT_ID, t.seq())
                .execute();

        db.update(ORDERS)
                .set(ORDERS.FILLED_QUANTITY, ORDERS.FILLED_QUANTITY.plus(t.quantity()))
                .set(ORDERS.LEAVES_QUANTITY, ORDERS.LEAVES_QUANTITY.minus(t.quantity()))
                .set(
                        ORDERS.STATUS,
                        DSL.when(ORDERS.LEAVES_QUANTITY.minus(t.quantity()).le(0L), "filled")
                                .otherwise("partially_filled"))
                .set(ORDERS.UPDATED_AT, t.simTime())
                .set(ORDERS.LAST_EVENT_ID, DSL.greatest(ORDERS.LAST_EVENT_ID, DSL.val(t.seq())))
                .where(ORDERS.ORDER_ID.eq(orderId))
                .execute();
    }

    private boolean accepted(OrderAccepted a) {
        return db.insertInto(ORDERS)
                        .set(ORDERS.ORDER_ID, a.orderId())
                        .set(ORDERS.ACCOUNT_ID, a.accountId())
                        .set(ORDERS.CLIENT_ORDER_ID, a.clientOrderId())
                        .set(ORDERS.SYMBOL, a.symbol())
                        .set(ORDERS.SIDE, a.side().name())
                        .set(ORDERS.ORDER_TYPE, a.orderType().name())
                        .set(ORDERS.PRICE, a.price())
                        .set(ORDERS.QUANTITY, a.quantity())
                        .set(ORDERS.FILLED_QUANTITY, 0L)
                        .set(ORDERS.LEAVES_QUANTITY, a.quantity())
                        .set(ORDERS.STATUS, "open")
                        .set(ORDERS.CREATED_AT, a.simTime())
                        .set(ORDERS.UPDATED_AT, a.simTime())
                        .set(ORDERS.LAST_EVENT_ID, a.seq())
                        .onConflictDoNothing()
                        .execute()
                > 0;
    }

    private boolean cancelled(OrderCancelled c) {
        return db.update(ORDERS)
                        .set(ORDERS.STATUS, "cancelled")
                        .set(ORDERS.LEAVES_QUANTITY, 0L)
                        .set(ORDERS.REASON, c.reason().name())
                        .set(ORDERS.UPDATED_AT, c.simTime())
                        .set(ORDERS.LAST_EVENT_ID, c.seq())
                        .where(ORDERS.ORDER_ID.eq(c.orderId()).and(ORDERS.LAST_EVENT_ID.lt(c.seq())))
                        .execute()
                > 0;
    }

    private boolean modified(OrderModified m) {
        return db.update(ORDERS)
                        .set(ORDERS.PRICE, m.price())
                        .set(ORDERS.QUANTITY, m.quantity())
                        .set(ORDERS.LEAVES_QUANTITY, m.leavesQuantity())
                        .set(ORDERS.UPDATED_AT, m.simTime())
                        .set(ORDERS.LAST_EVENT_ID, m.seq())
                        .where(ORDERS.ORDER_ID.eq(m.orderId()).and(ORDERS.LAST_EVENT_ID.lt(m.seq())))
                        .execute()
                > 0;
    }

    private boolean rejected(OrderRejected r) {
        if (r.orderId() != 0) {
            return true; // a refused cancel or modify: the order itself is unchanged
        }
        return db.insertInto(REJECTIONS)
                        .set(REJECTIONS.EVENT_ID, r.seq())
                        .set(REJECTIONS.ACCOUNT_ID, r.accountId())
                        .set(REJECTIONS.CLIENT_ORDER_ID, r.clientOrderId())
                        .set(REJECTIONS.SYMBOL, r.symbol())
                        .set(REJECTIONS.REASON, r.reason().name())
                        .set(REJECTIONS.SIM_TIME, r.simTime())
                        .onConflictDoNothing()
                        .execute()
                > 0;
    }

    private void progress(int partition, long eventId, long count) {
        db.insertInto(CONSUMER_PROGRESS)
                .set(CONSUMER_PROGRESS.TOPIC_PARTITION, partition)
                .set(CONSUMER_PROGRESS.LAST_EVENT_ID, eventId)
                .set(CONSUMER_PROGRESS.EVENTS, count)
                .onConflict(CONSUMER_PROGRESS.TOPIC_PARTITION)
                .doUpdate()
                .set(CONSUMER_PROGRESS.LAST_EVENT_ID, DSL.greatest(CONSUMER_PROGRESS.LAST_EVENT_ID, DSL.val(eventId)))
                .set(CONSUMER_PROGRESS.EVENTS, CONSUMER_PROGRESS.EVENTS.plus(count))
                .execute();
    }
}
