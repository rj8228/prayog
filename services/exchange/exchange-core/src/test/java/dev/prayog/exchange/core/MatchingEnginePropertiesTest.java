package dev.prayog.exchange.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;

/**
 * Property tests: jqwik generates thousands of random command flows and checks that rules hold for every one. When a
 * check fails, jqwik shrinks the flow to the shortest one that still fails and prints it.
 *
 * <p>Prices are drawn from only 7 ticks so orders cross often, queues are long, and many modifies keep their price
 * (the reduce-in-place path). Cancels and modifies aim at low order IDs so
 * most of them hit a real order.
 */
class MatchingEnginePropertiesTest {

    private static final String ABC = "ABC";
    private static final long TICK = 5;
    private static final Instrument INSTRUMENT = new Instrument(ABC, TICK, 1_000, 10_000, 20);
    private static final long T0 = 1_790_000_000_000_000L;

    @Property
    void bookIsNeverCrossedAndStaysConsistent(@ForAll("flows") List<Command> flow) {
        MatchingEngine engine = openEngine(e -> {});
        OrderBook book = engine.book(ABC);
        for (Command command : flow) {
            engine.apply(command);
            book.checkInvariants(); // includes best bid < best ask
        }
    }

    @Property
    void bookIsEmptyWheneverTheMarketIsClosed(@ForAll("flows") List<Command> flow) {
        MatchingEngine engine = openEngine(e -> {});
        for (Command command : flow) {
            engine.apply(command);
            if (command instanceof SetSessionState s && s.state() == SessionState.CLOSED) {
                assertThat(engine.book(ABC).orderCount()).isZero();
            }
        }
    }

    @Property
    void noAccountEverTradesWithItself(@ForAll("flows") List<Command> flow) {
        assertThat(run(flow).events)
                .filteredOn(Trade.class::isInstance)
                .allSatisfy(e -> assertThat(((Trade) e).buyAccountId()).isNotEqualTo(((Trade) e).sellAccountId()));
    }

    /** For every order: ordered = filled + still open + cancelled, where "ordered" follows modifies. */
    @Property
    void quantityIsConserved(@ForAll("flows") List<Command> flow) {
        Run run = run(flow);
        Map<Long, Long> ordered = new HashMap<>();
        Map<Long, Long> done = new HashMap<>();
        long bought = 0;
        long sold = 0;
        for (ExchangeEvent event : run.events) {
            switch (event) {
                case OrderAccepted a -> ordered.put(a.orderId(), a.quantity());
                case OrderModified m -> ordered.put(m.orderId(), m.quantity());
                case OrderCancelled c -> done.merge(c.orderId(), c.cancelledQuantity(), Long::sum);
                case Trade t -> {
                    done.merge(t.buyOrderId(), t.quantity(), Long::sum);
                    done.merge(t.sellOrderId(), t.quantity(), Long::sum);
                    bought += t.quantity();
                    sold += t.quantity();
                }
                default -> {}
            }
        }
        for (Map.Entry<Long, Long> order : ordered.entrySet()) {
            long id = order.getKey();
            assertThat(done.getOrDefault(id, 0L) + run.book.leavesQuantity(id))
                    .as("order %d: filled + cancelled + open == ordered", id)
                    .isEqualTo(order.getValue());
        }
        assertThat(bought).isEqualTo(sold);
    }

    @Property
    void tradesHappenAtTheRestingPriceWithinBothLimits(@ForAll("flows") List<Command> flow) {
        Map<Long, Long> price = new HashMap<>(); // current limit; 0 = market order
        for (ExchangeEvent event : run(flow).events) {
            switch (event) {
                case OrderAccepted a -> price.put(a.orderId(), a.price());
                case OrderModified m -> price.put(m.orderId(), m.price());
                case Trade t -> {
                    long buy = price.get(t.buyOrderId());
                    long sell = price.get(t.sellOrderId());
                    long passive = t.aggressorSide() == Side.BUY ? sell : buy;
                    assertThat(t.price()).isEqualTo(passive);
                    if (buy != 0) {
                        assertThat(t.price()).isLessThanOrEqualTo(buy);
                    }
                    if (sell != 0) {
                        assertThat(t.price()).isGreaterThanOrEqualTo(sell);
                    }
                    assertThat(t.quantity()).isPositive();
                }
                default -> {}
            }
        }
    }

    @Property
    void marketOrdersNeverRest(@ForAll("flows") List<Command> flow) {
        Run run = run(flow);
        run.events.stream()
                .filter(e -> e instanceof OrderAccepted a && a.orderType() == OrderType.MARKET)
                .forEach(e -> assertThat(run.book.leavesQuantity(((OrderAccepted) e).orderId()))
                        .isZero());
    }

    @Property
    void sequenceNumbersAreGapFree(@ForAll("flows") List<Command> flow) {
        List<ExchangeEvent> events = run(flow).events;
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).seq()).isEqualTo(i + 1L);
        }
    }

    @Property
    void sameInputsGiveSameEvents(@ForAll("flows") List<Command> flow) {
        assertThat(run(flow).events).isEqualTo(run(flow).events);
    }

    @Property
    void matchesTheReferenceMatcher(@ForAll("flows") List<Command> flow) {
        ReferenceMatcher reference = new ReferenceMatcher(Map.of(ABC, INSTRUMENT));
        reference.apply(new ClockTick(T0));
        reference.apply(new SetRules(MatchingEngine.LATEST_RULES));
        reference.apply(new SetSessionState(SessionState.OPEN));
        flow.forEach(reference::apply);
        assertThat(run(flow).events).isEqualTo(reference.events);
    }

    private record Run(List<ExchangeEvent> events, OrderBook book) {}

    private static Run run(List<Command> flow) {
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = openEngine(events::add);
        flow.forEach(engine::apply);
        return new Run(events, engine.book(ABC));
    }

    /** Engines start CLOSED; every flow starts from an open market at T0. */
    private static MatchingEngine openEngine(EventSink sink) {
        MatchingEngine engine = new MatchingEngine(List.of(INSTRUMENT), sink);
        engine.apply(new ClockTick(T0));
        engine.apply(new SetRules(MatchingEngine.LATEST_RULES));
        engine.apply(new SetSessionState(SessionState.OPEN));
        return engine;
    }

    /** Mostly limit orders around 100.00 rupees, plus market orders, cancels, modifies, ticks and a few bad inputs. */
    @Provide
    Arbitrary<List<Command>> flows() {
        Arbitrary<Long> account = Arbitraries.longs().between(1, 4);
        Arbitrary<Long> price = Arbitraries.longs().between(1_997, 2_003).map(ticks -> ticks * TICK);
        Arbitrary<Long> quantity = Arbitraries.longs().between(1, 50);
        Arbitrary<Long> orderId = Arbitraries.longs().between(1, 60);
        // A small pool per account, so some IDs repeat: duplicates are part of the flow (ADR 0014).
        Arbitrary<Integer> clientId = Arbitraries.integers().between(1, 40);

        Arbitrary<Command> limit = Combinators.combine(account, Arbitraries.of(Side.class), price, quantity, clientId)
                .as((a, side, p, q, c) -> new NewOrder("c-" + c, a, ABC, side, OrderType.LIMIT, p, q));
        Arbitrary<Command> market = Combinators.combine(account, Arbitraries.of(Side.class), quantity, clientId)
                .as((a, side, q, c) -> new NewOrder("m-" + c, a, ABC, side, OrderType.MARKET, 0, q));
        Arbitrary<Command> cancel =
                Combinators.combine(account, orderId).as((a, id) -> new CancelOrder("x-" + id, a, ABC, id));
        Arbitrary<Command> modify = Combinators.combine(account, orderId, price, quantity)
                .as((a, id, p, q) -> new ModifyOrder("x-" + id, a, ABC, id, p, q));
        Arbitrary<Command> tick = Arbitraries.longs().between(0, 1_000_000).map(dt -> new ClockTick(T0 + dt));
        Arbitrary<Command> invalid = Arbitraries.of(
                new NewOrder("bad-symbol", 1, "ZZZ", Side.BUY, OrderType.LIMIT, 10_000, 1),
                new NewOrder("bad-qty", 1, ABC, Side.BUY, OrderType.LIMIT, 10_000, 0),
                new NewOrder("too-big", 1, ABC, Side.SELL, OrderType.LIMIT, 10_000, 1_001),
                new NewOrder("bad-price", 1, ABC, Side.SELL, OrderType.LIMIT, 0, 1),
                new NewOrder("off-tick", 1, ABC, Side.BUY, OrderType.LIMIT, 10_001, 1),
                new NewOrder("priced-market", 1, ABC, Side.BUY, OrderType.MARKET, 10_000, 1),
                new NewOrder("above-band", 1, ABC, Side.SELL, OrderType.LIMIT, 12_005, 1),
                new NewOrder("below-band", 1, ABC, Side.BUY, OrderType.LIMIT, 7_995, 1),
                new ModifyOrder("bad-modify", 1, ABC, 1, 10_001, 5),
                new ModifyOrder("band-modify", 1, ABC, 1, 12_005, 5),
                new CancelOrder("bad-cancel", 1, "ZZZ", 1));
        // Mostly OPEN so trading continues; HALTED and CLOSED exercise the session rules and DAY expiry.
        Arbitrary<Command> session = Arbitraries.frequencyOf(
                        Tuple.of(6, Arbitraries.just(SessionState.OPEN)),
                        Tuple.of(3, Arbitraries.just(SessionState.HALTED)),
                        Tuple.of(1, Arbitraries.just(SessionState.CLOSED)))
                .map(SetSessionState::new);
        Arbitrary<Command> killSwitch =
                Combinators.combine(account, Arbitraries.of(true, false)).as(SetAccountEnabled::new);

        return Arbitraries.frequencyOf(
                        Tuple.of(50, limit),
                        Tuple.of(8, market),
                        Tuple.of(14, cancel),
                        Tuple.of(14, modify),
                        Tuple.of(6, tick),
                        Tuple.of(8, invalid),
                        Tuple.of(4, session),
                        Tuple.of(3, killSwitch))
                .list()
                .ofMaxSize(200);
    }
}
