package dev.prayog.posttrade.ledger;

import static dev.prayog.posttrade.db.Tables.ORDERS;
import static dev.prayog.posttrade.db.Tables.POSITIONS;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The ledger against a real PostgreSQL with the real migrations. The S16 acceptance test lives here: P&L reconciles
 * exactly with the trade log, and redelivering the whole log changes nothing.
 */
@Testcontainers
class LedgerIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    private static PGSimpleDataSource dataSource;

    private static final Charges CHARGES = new Charges(300, 2_000, 35);
    private static final List<String> SYMBOLS = List.of("INFY", "TCS", "HDFCBANK");

    private DSLContext db;
    private Ledger ledger;
    private LedgerQueries queries;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void clean() {
        db = DSL.using(dataSource, SQLDialect.POSTGRES);
        for (String table : List.of(
                "trades",
                "fills",
                "positions",
                "marks",
                "orders",
                "rejections",
                "consumer_progress",
                "account_names")) {
            db.execute("truncate table " + table);
        }
        ledger = new Ledger(db, CHARGES);
        queries = new LedgerQueries(db);
    }

    @Test
    void pnlReconcilesExactlyWithTheTradeLogAndRedeliveryChangesNothing() {
        List<ExchangeEvent> log = randomSession(new Random(7), 3_000);
        apply(log);

        List<Trade> trades = log.stream()
                .filter(Trade.class::isInstance)
                .map(Trade.class::cast)
                .toList();
        Map<String, Long> marks = new HashMap<>();
        trades.forEach(t -> marks.put(t.symbol(), t.price()));
        Map<Long, long[]> expected = expectedFromTradeLog(trades, marks); // account -> {cash+mtm, charges}
        Map<Long, LedgerQueries.Pnl> actual = queries.pnl(null);

        assertThat(actual.keySet()).isEqualTo(expected.keySet());
        expected.forEach((account, e) -> {
            LedgerQueries.Pnl p = actual.get(account);
            assertThat(p.realisedPnl() + p.unrealisedPnl())
                    .as("P&L before charges, account " + account)
                    .isEqualTo(e[0]);
            assertThat(p.charges()).as("charges, account " + account).isEqualTo(e[1]);
            assertThat(p.netPnl()).isEqualTo(e[0] - e[1]);
        });

        LedgerQueries.Totals totals = queries.totals();
        assertThat(totals.trades()).isEqualTo(trades.size());
        assertThat(totals.pnlBeforeCharges()).as("a market is zero-sum").isZero();
        assertThat(totals.netQuantityBySymbol().values()).allMatch(q -> q == 0);

        // Redeliver everything, shuffled (at-least-once delivery can repeat and reorder): nothing changes.
        var before = db.selectFrom(POSITIONS)
                .orderBy(POSITIONS.ACCOUNT_ID, POSITIONS.SYMBOL)
                .fetch()
                .intoMaps();
        var ordersBefore =
                db.selectFrom(ORDERS).orderBy(ORDERS.ORDER_ID).fetch().intoMaps();
        List<ExchangeEvent> again = new ArrayList<>(log);
        Collections.shuffle(again, new Random(1));
        Ledger.Changes changes = apply(again);
        assertThat(changes.applied())
                .as("only non-ledger events count as applied")
                .isEqualTo((int)
                        again.stream().filter(e -> e instanceof BookUpdate).count());
        assertThat(db.selectFrom(POSITIONS)
                        .orderBy(POSITIONS.ACCOUNT_ID, POSITIONS.SYMBOL)
                        .fetch()
                        .intoMaps())
                .isEqualTo(before);
        assertThat(db.selectFrom(ORDERS).orderBy(ORDERS.ORDER_ID).fetch().intoMaps())
                .isEqualTo(ordersBefore);
        assertThat(queries.totals().trades()).isEqualTo(trades.size());
    }

    @Test
    void anOrdersLifeShowsInTheBlotter() {
        apply(List.of(
                new OrderAccepted(1, 10, 100, "c-1", 7, "INFY", Side.BUY, OrderType.LIMIT, 150_000, 10),
                new OrderAccepted(2, 11, 101, "c-2", 8, "INFY", Side.SELL, OrderType.LIMIT, 150_000, 4),
                new Trade(3, 11, 1, "INFY", 150_000, 4, Side.SELL, 100, 101, 7, 8),
                new OrderModified(4, 12, 100, 7, "INFY", 149_500, 8, 4),
                new OrderCancelled(5, 13, 100, 7, "INFY", 4, CancelReason.CLIENT_REQUEST),
                new OrderRejected(6, 14, 0, "c-3", 7, "INFY", RejectReason.PRICE_NOT_ON_TICK),
                new OrderRejected(7, 15, 100, "c-4", 7, "INFY", RejectReason.UNKNOWN_ORDER)));

        List<LedgerQueries.OrderView> orders = queries.orders(7, 10);
        assertThat(orders).hasSize(1);
        LedgerQueries.OrderView o = orders.getFirst();
        assertThat(o.status()).isEqualTo("cancelled");
        assertThat(o.reason()).isEqualTo("CLIENT_REQUEST");
        assertThat(o.filledQuantity()).isEqualTo(4);
        assertThat(o.leavesQuantity()).isZero();
        assertThat(o.price()).isEqualTo(149_500);
        assertThat(queries.orders(8, 10).getFirst().status()).isEqualTo("filled");
        assertThat(queries.rejections(7, 10))
                .extracting(LedgerQueries.RejectionView::reason)
                .containsExactly("PRICE_NOT_ON_TICK");

        // An old event arriving late does not undo a newer one.
        apply(List.of(new OrderModified(4, 12, 100, 7, "INFY", 149_500, 8, 4)));
        assertThat(queries.orders(7, 10).getFirst().status()).isEqualTo("cancelled");
    }

    @Test
    void positionsAndFillsForOneAccount() {
        apply(List.of(
                new Trade(1, 1, 1, "TCS", 400_000, 10, Side.BUY, 1, 2, 7, 8),
                new Trade(2, 2, 2, "TCS", 410_000, 4, Side.SELL, 3, 4, 9, 7)));
        List<LedgerQueries.PositionView> positions = queries.positions(7);
        assertThat(positions).hasSize(1);
        LedgerQueries.PositionView p = positions.getFirst();
        assertThat(p.quantity()).isEqualTo(6);
        assertThat(p.realisedPnl()).isEqualTo(4 * 10_000);
        assertThat(p.mark()).isEqualTo(410_000);
        assertThat(p.unrealisedPnl()).isEqualTo(6 * 10_000);
        assertThat(p.charges()).isEqualTo(CHARGES.on(4_000_000) + CHARGES.on(1_640_000));
        assertThat(queries.fills(7, 10))
                .extracting(LedgerQueries.FillView::side)
                .containsExactly("SELL", "BUY");
        assertThat(queries.holders(List.of("TCS"))).containsExactlyInAnyOrder(7L, 8L, 9L);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private Ledger.Changes apply(List<ExchangeEvent> events) {
        Ledger.Changes total = new Ledger.Changes(new java.util.TreeSet<>(), new java.util.TreeSet<>(), 0, 0);
        int applied = 0;
        int duplicates = 0;
        for (int i = 0; i < events.size(); i += 250) { // like Kafka batches
            List<Ledger.Incoming> batch = events.subList(i, Math.min(events.size(), i + 250)).stream()
                    .map(e -> new Ledger.Incoming(partition(e), e))
                    .toList();
            Ledger.Changes c = db.transactionResult(tx -> new Ledger(tx.dsl(), CHARGES).apply(batch));
            applied += c.applied();
            duplicates += c.duplicates();
        }
        return new Ledger.Changes(total.accounts(), total.markedSymbols(), applied, duplicates);
    }

    private static int partition(ExchangeEvent e) {
        return Math.floorMod(dev.prayog.contracts.event.EventJson.key(e).hashCode(), 3);
    }

    /** An independent, naive computation: cash paid and received plus open quantity at the mark; charges per fill. */
    private static Map<Long, long[]> expectedFromTradeLog(List<Trade> trades, Map<String, Long> marks) {
        Map<Long, long[]> out = new HashMap<>();
        Map<String, Long> quantity = new HashMap<>(); // account/symbol -> qty
        for (Trade t : trades) {
            long value = t.price() * t.quantity();
            long[] buyer = out.computeIfAbsent(t.buyAccountId(), k -> new long[2]);
            long[] seller = out.computeIfAbsent(t.sellAccountId(), k -> new long[2]);
            buyer[0] -= value;
            seller[0] += value;
            buyer[1] += CHARGES.on(value);
            seller[1] += CHARGES.on(value);
            quantity.merge(t.buyAccountId() + "/" + t.symbol(), t.quantity(), Long::sum);
            quantity.merge(t.sellAccountId() + "/" + t.symbol(), -t.quantity(), Long::sum);
        }
        quantity.forEach((key, q) -> {
            String[] parts = key.split("/");
            out.get(Long.parseLong(parts[0]))[0] += q * marks.get(parts[1]);
        });
        return out;
    }

    /** Accepted orders, trades between different accounts, cancels, modifies, rejections and book updates. */
    private static List<ExchangeEvent> randomSession(Random random, int steps) {
        List<ExchangeEvent> events = new ArrayList<>();
        long seq = 1;
        long orderId = 1;
        long tradeId = 1;
        Map<String, Long> price = new HashMap<>();
        SYMBOLS.forEach(s -> price.put(s, 150_000L));
        for (int i = 0; i < steps; i++) {
            String symbol = SYMBOLS.get(random.nextInt(SYMBOLS.size()));
            long p = price.merge(symbol, (random.nextInt(11) - 5) * 5L, Long::sum);
            long buyer = 1 + random.nextInt(8);
            long seller = buyer % 8 + 1; // never the same account (self-trades are prevented)
            long quantity = 1 + random.nextInt(200);
            long buyOrder = orderId++;
            long sellOrder = orderId++;
            long t = i * 1_000L;
            events.add(new OrderAccepted(
                    seq++, t, sellOrder, "s" + i, seller, symbol, Side.SELL, OrderType.LIMIT, p, quantity));
            events.add(new BookUpdate(seq++, t, symbol, Side.SELL, p, quantity, 1));
            events.add(new OrderAccepted(
                    seq++, t, buyOrder, "b" + i, buyer, symbol, Side.BUY, OrderType.LIMIT, p, quantity + 5));
            events.add(
                    new Trade(seq++, t, tradeId++, symbol, p, quantity, Side.BUY, buyOrder, sellOrder, buyer, seller));
            events.add(new BookUpdate(seq++, t, symbol, Side.SELL, p, 0, 0));
            if (random.nextBoolean()) {
                events.add(new OrderCancelled(seq++, t, buyOrder, buyer, symbol, 5, CancelReason.CLIENT_REQUEST));
            } else {
                events.add(new OrderModified(seq++, t, buyOrder, buyer, symbol, p - 5, quantity + 3, 3));
            }
            if (random.nextInt(10) == 0) {
                events.add(new OrderRejected(seq++, t, 0, "r" + i, buyer, symbol, RejectReason.PRICE_OUTSIDE_BAND));
            }
        }
        return events;
    }
}
