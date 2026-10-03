package dev.prayog.exchange.core;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
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

/**
 * Property tests: jqwik generates thousands of random order flows and checks that rules hold for every one. When a
 * check fails, jqwik shrinks the flow to the shortest one that still fails and prints it.
 *
 * <p>Prices are drawn from a narrow range so orders cross often and most flows produce trades.
 */
class MatchingEnginePropertiesTest {

    private static final String ABC = "ABC";
    private static final long TICK = 5;
    private static final Instrument INSTRUMENT = new Instrument(ABC, TICK, 1_000);
    private static final long T0 = 1_790_000_000_000_000L;

    @Property
    void bookIsNeverCrossedAndStaysConsistent(@ForAll("orderFlows") List<NewOrder> flow) {
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(List.of(INSTRUMENT), events::add);
        OrderBook book = engine.book(ABC);
        for (NewOrder order : flow) {
            engine.submit(order, T0);
            book.checkInvariants(); // includes best bid < best ask
        }
    }

    @Property
    void quantityIsConserved(@ForAll("orderFlows") List<NewOrder> flow) {
        List<ExchangeEvent> events = run(flow);
        OrderBook book = lastBook;

        Map<Long, Long> filled = new HashMap<>();
        for (ExchangeEvent event : events) {
            if (event instanceof Trade t) {
                filled.merge(t.buyOrderId(), t.quantity(), Long::sum);
                filled.merge(t.sellOrderId(), t.quantity(), Long::sum);
            }
        }
        long bought = 0;
        long sold = 0;
        for (ExchangeEvent event : events) {
            if (event instanceof OrderAccepted a) {
                long done = filled.getOrDefault(a.orderId(), 0L);
                assertThat(done + book.leavesQuantity(a.orderId()))
                        .as("order %d: filled + open == ordered", a.orderId())
                        .isEqualTo(a.quantity());
                if (a.side() == Side.BUY) {
                    bought += done;
                } else {
                    sold += done;
                }
            }
        }
        assertThat(bought).as("every share bought was sold").isEqualTo(sold);
    }

    @Property
    void tradesHappenAtTheRestingPriceWithinBothLimits(@ForAll("orderFlows") List<NewOrder> flow) {
        List<ExchangeEvent> events = run(flow);
        Map<Long, OrderAccepted> accepted = new HashMap<>();
        for (ExchangeEvent event : events) {
            if (event instanceof OrderAccepted a) {
                accepted.put(a.orderId(), a);
            } else if (event instanceof Trade t) {
                OrderAccepted buy = accepted.get(t.buyOrderId());
                OrderAccepted sell = accepted.get(t.sellOrderId());
                OrderAccepted passive = t.aggressorSide() == Side.BUY ? sell : buy;
                assertThat(t.price()).isEqualTo(passive.price());
                assertThat(t.price()).isBetween(sell.price(), buy.price());
                assertThat(t.quantity()).isPositive();
            }
        }
    }

    @Property
    void sequenceNumbersAreGapFree(@ForAll("orderFlows") List<NewOrder> flow) {
        List<ExchangeEvent> events = run(flow);
        for (int i = 0; i < events.size(); i++) {
            assertThat(events.get(i).seq()).isEqualTo(i + 1L);
        }
    }

    @Property
    void sameInputsGiveSameEvents(@ForAll("orderFlows") List<NewOrder> flow) {
        assertThat(run(flow)).isEqualTo(run(flow));
    }

    @Property
    void matchesTheReferenceMatcher(@ForAll("orderFlows") List<NewOrder> flow) {
        ReferenceMatcher reference = new ReferenceMatcher(Map.of(ABC, INSTRUMENT));
        for (NewOrder order : flow) {
            reference.submit(order, T0);
        }
        assertThat(run(flow)).isEqualTo(reference.events);
    }

    private OrderBook lastBook;

    private List<ExchangeEvent> run(List<NewOrder> flow) {
        List<ExchangeEvent> events = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(List.of(INSTRUMENT), events::add);
        for (NewOrder order : flow) {
            engine.submit(order, T0);
        }
        lastBook = engine.book(ABC);
        return events;
    }

    /** Mostly valid limit orders around 100.00 rupees, with about one in ten invalid. */
    @Provide
    Arbitrary<List<NewOrder>> orderFlows() {
        Arbitrary<NewOrder> valid = Combinators.combine(
                        Arbitraries.longs().between(1, 4),
                        Arbitraries.of(Side.class),
                        Arbitraries.longs().between(1_990, 2_010).map(ticks -> ticks * TICK),
                        Arbitraries.longs().between(1, 50))
                .as((account, side, price, qty) ->
                        new NewOrder("c-" + account, account, ABC, side, OrderType.LIMIT, price, qty));
        Arbitrary<NewOrder> invalid = Arbitraries.of(
                new NewOrder("bad-symbol", 1, "ZZZ", Side.BUY, OrderType.LIMIT, 10_000, 1),
                new NewOrder("bad-qty", 1, ABC, Side.BUY, OrderType.LIMIT, 10_000, 0),
                new NewOrder("too-big", 1, ABC, Side.SELL, OrderType.LIMIT, 10_000, 1_001),
                new NewOrder("bad-price", 1, ABC, Side.SELL, OrderType.LIMIT, 0, 1),
                new NewOrder("off-tick", 1, ABC, Side.BUY, OrderType.LIMIT, 10_001, 1));
        return Arbitraries.frequencyOf(net.jqwik.api.Tuple.of(9, valid), net.jqwik.api.Tuple.of(1, invalid))
                .list()
                .ofMaxSize(200);
    }
}
