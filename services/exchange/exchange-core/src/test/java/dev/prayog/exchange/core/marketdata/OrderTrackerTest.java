package dev.prayog.exchange.core.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.DepthLevel;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.ModifyOrder;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.OrderBook;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.SetAccountEnabled;
import dev.prayog.exchange.core.SetSessionState;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

class OrderTrackerTest {

    private static final List<Instrument> INSTRUMENTS =
            List.of(new Instrument("INFY", 5, 1_000, 10_000, 10), new Instrument("TCS", 10, 500, 40_000, 5));
    private static final SessionSchedule SCHEDULE =
            new SessionSchedule(LocalTime.of(9, 15), LocalTime.of(15, 30), ZoneOffset.ofHoursMinutes(5, 30));
    /** 2026-10-05 09:30 IST, in epoch microseconds: the market is open. */
    private static final long OPEN_TIME =
            LocalDateTime.of(2026, 10, 5, 9, 30).toEpochSecond(SCHEDULE.offset()) * 1_000_000L;

    private static final long MINUTE = 60_000_000L;

    /**
     * After every command, three views of the book must agree: the engine's own, the tracker's, and one rebuilt only
     * from the level changes the tracker reported (what a market-data client sees). Open orders must agree too.
     */
    @Property(tries = 60)
    void theTrackedBookAlwaysEqualsTheEngineBook(@ForAll long seed) {
        Random random = new Random(seed);
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(INSTRUMENTS, SCHEDULE, events::add);
        OrderTracker tracker = new OrderTracker();
        Map<String, Map<Side, TreeMap<Long, long[]>>> client = new HashMap<>();
        List<OrderAccepted> known = new ArrayList<>();
        long time = OPEN_TIME;

        for (int i = 0; i < 600; i++) {
            if (random.nextInt(25) == 0) {
                time += random.nextInt(8) == 0 ? 7 * 60 * MINUTE : MINUTE; // sometimes cross the close
            }
            Command command = randomCommand(random, known, time);
            events.clear();
            engine.apply(command);
            for (ExchangeEvent event : events) {
                tracker.onEvent(event);
                if (event instanceof OrderAccepted a) {
                    known.add(a);
                }
            }
            for (OrderTracker.LevelChange change : tracker.endCommand()) {
                TreeMap<Long, long[]> side = client.computeIfAbsent(change.symbol(), k -> new HashMap<>())
                        .computeIfAbsent(change.side(), k -> new TreeMap<>());
                if (change.quantity() == 0) {
                    side.remove(change.price());
                } else {
                    side.put(change.price(), new long[] {change.quantity(), change.orderCount()});
                }
            }

            for (Instrument instrument : INSTRUMENTS) {
                String symbol = instrument.symbol();
                OrderBook book = engine.book(symbol);
                for (Side side : Side.values()) {
                    List<DepthLevel> expected = book.depth(side, Integer.MAX_VALUE);
                    assertThat(tracker.depth(symbol, side, Integer.MAX_VALUE))
                            .as("tracker %s %s after command %d: %s", symbol, side, i, command)
                            .isEqualTo(expected);
                    assertThat(fromClient(client, symbol, side))
                            .as("client %s %s after command %d", symbol, side, i)
                            .isEqualTo(expected);
                }
            }
            for (long account = 1; account <= 4; account++) {
                for (OrderTracker.OpenOrder open : tracker.openOrders(account)) {
                    assertThat(engine.book(open.symbol()).leavesQuantity(open.orderId()))
                            .as("leaves of order %d", open.orderId())
                            .isEqualTo(open.leavesQuantity());
                }
            }
        }
    }

    @Test
    void reportsOnlyLevelsThatReallyChanged() {
        OrderTracker tracker = new OrderTracker();
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(INSTRUMENTS, SCHEDULE, events::add);
        engine.apply(new ClockTick(OPEN_TIME));
        engine.apply(new NewOrder("a", 1, "INFY", Side.SELL, OrderType.LIMIT, 10_000, 10));
        events.forEach(tracker::onEvent);
        assertThat(tracker.endCommand())
                .containsExactly(new OrderTracker.LevelChange("INFY", Side.SELL, 10_000, 10, 1));

        // A buy that crosses completely: it never rests, so its own price level must not be reported.
        events.clear();
        engine.apply(new NewOrder("b", 2, "INFY", Side.BUY, OrderType.LIMIT, 10_050, 4));
        events.forEach(tracker::onEvent);
        assertThat(tracker.endCommand()).containsExactly(new OrderTracker.LevelChange("INFY", Side.SELL, 10_000, 6, 1));
        assertThat(tracker.openOrders(2)).isEmpty();
        assertThat(tracker.openOrders(1))
                .singleElement()
                .satisfies(o -> assertThat(o.leavesQuantity()).isEqualTo(6));
    }

    @Test
    void tracksTheSession() {
        OrderTracker tracker = new OrderTracker();
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(INSTRUMENTS, SCHEDULE, events::add);
        engine.apply(new ClockTick(OPEN_TIME));
        events.forEach(tracker::onEvent);
        assertThat(tracker.session()).isEqualTo(SessionState.OPEN);
    }

    private static List<DepthLevel> fromClient(
            Map<String, Map<Side, TreeMap<Long, long[]>>> client, String symbol, Side side) {
        TreeMap<Long, long[]> levels = client.getOrDefault(symbol, Map.of()).getOrDefault(side, new TreeMap<>());
        List<Map.Entry<Long, long[]>> entries = new ArrayList<>(levels.entrySet());
        if (side == Side.BUY) {
            entries.sort(Map.Entry.<Long, long[]>comparingByKey(Comparator.reverseOrder()));
        }
        return entries.stream()
                .map(e -> new DepthLevel(e.getKey(), e.getValue()[0], (int) e.getValue()[1]))
                .toList();
    }

    private static Command randomCommand(Random random, List<OrderAccepted> known, long time) {
        Instrument instrument = INSTRUMENTS.get(random.nextInt(INSTRUMENTS.size()));
        long account = 1 + random.nextInt(4);
        String id = "c" + random.nextInt(1_000_000);
        int roll = random.nextInt(100);
        OrderAccepted target =
                known.isEmpty() ? null : known.get(known.size() - 1 - random.nextInt(Math.min(known.size(), 30)));
        if (roll < 5) {
            return new ClockTick(time);
        } else if (roll < 55) {
            long tick = instrument.tickSize();
            long price = instrument.referencePrice() + (random.nextInt(21) - 10) * tick;
            return new NewOrder(
                    id,
                    account,
                    instrument.symbol(),
                    random.nextBoolean() ? Side.BUY : Side.SELL,
                    OrderType.LIMIT,
                    price,
                    1 + random.nextInt(40));
        } else if (roll < 63) {
            return new NewOrder(
                    id,
                    account,
                    instrument.symbol(),
                    random.nextBoolean() ? Side.BUY : Side.SELL,
                    OrderType.MARKET,
                    0,
                    1 + random.nextInt(40));
        } else if (roll < 78 && target != null) {
            return new CancelOrder(id, target.accountId(), target.symbol(), target.orderId());
        } else if (roll < 95 && target != null) {
            Instrument ti = INSTRUMENTS.stream()
                    .filter(i -> i.symbol().equals(target.symbol()))
                    .findFirst()
                    .orElseThrow();
            long price = random.nextBoolean()
                    ? target.price()
                    : ti.referencePrice() + (random.nextInt(21) - 10) * ti.tickSize();
            return new ModifyOrder(
                    id, target.accountId(), target.symbol(), target.orderId(), price, 1 + random.nextInt(60));
        } else if (roll < 97) {
            return new SetAccountEnabled(account, random.nextBoolean());
        } else {
            return new SetSessionState(random.nextBoolean() ? SessionState.HALTED : SessionState.OPEN);
        }
    }
}
