package dev.prayog.exchange.core;

import static dev.prayog.contracts.Side.BUY;
import static dev.prayog.contracts.Side.SELL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MatchingEngineTest {

    private static final long T0 = 1_790_000_000_000_000L;
    private static final String ABC = "ABC";

    private final List<ExchangeEvent> events = new ArrayList<>();
    private final MatchingEngine engine =
            new MatchingEngine(List.of(new Instrument(ABC, 5, 1_000_000, 10_000, 20)), events::add);
    private final OrderBook book = engine.book(ABC);

    {
        engine.apply(new ClockTick(T0));
        engine.apply(new SetSessionState(SessionState.OPEN));
        events.clear(); // tests see only their own events; sequence numbers start at 2
    }

    @AfterEach
    void bookStaysConsistent() {
        book.checkInvariants();
    }

    @Test
    void nonCrossingOrdersRest() {
        limit(1, BUY, 10_000, 10);
        limit(2, SELL, 10_010, 5);

        assertThat(book.bestBid()).isEqualTo(10_000);
        assertThat(book.bestAsk()).isEqualTo(10_010);
        assertThat(trades()).isEmpty();
        assertThat(book.depth(BUY, 5)).containsExactly(new DepthLevel(10_000, 10, 1));
        assertThat(book.depth(SELL, 5)).containsExactly(new DepthLevel(10_010, 5, 1));
    }

    @Test
    void acceptedEventCarriesTheOrder() {
        limit(7, BUY, 10_000, 10);

        assertThat(events)
                .containsExactly(new OrderAccepted(2, T0, 1, "c-7", 7, ABC, BUY, OrderType.LIMIT, 10_000, 10));
    }

    @Test
    void tradeHappensAtTheRestingPrice() {
        limit(1, SELL, 10_000, 10);
        limit(2, BUY, 10_050, 10); // willing to pay more; gets the better resting price

        assertThat(trades()).containsExactly(new Trade(4, T0, 1, ABC, 10_000, 10, BUY, 2, 1, 2, 1));
        assertThat(book.orderCount()).isZero();
    }

    @Test
    void sellAggressorPutsIdsOnTheRightSides() {
        limit(1, BUY, 10_000, 4);
        limit(2, SELL, 9_990, 4);

        assertThat(trades()).containsExactly(new Trade(4, T0, 1, ABC, 10_000, 4, SELL, 1, 2, 1, 2));
    }

    @Test
    void partialFillRestsTheRemainder() {
        limit(1, SELL, 10_000, 3);
        limit(2, BUY, 10_000, 10);

        assertThat(trades()).extracting(Trade::quantity).containsExactly(3L);
        assertThat(book.bestBid()).isEqualTo(10_000);
        assertThat(book.leavesQuantity(2)).isEqualTo(7);
        assertThat(book.bestAsk()).isZero();
    }

    @Test
    void partiallyFilledRestingOrderKeepsItsPlace() {
        limit(1, SELL, 10_000, 10);
        limit(2, SELL, 10_000, 10);
        limit(3, BUY, 10_000, 4);
        limit(4, BUY, 10_000, 4);

        assertThat(trades()).extracting(Trade::sellOrderId).containsExactly(1L, 1L);
        assertThat(book.leavesQuantity(1)).isEqualTo(2);
    }

    @Test
    void aggressorSweepsLevelsBestPriceFirst() {
        limit(1, SELL, 10_010, 5);
        limit(2, SELL, 10_000, 5);
        limit(3, SELL, 10_020, 5);
        limit(4, BUY, 10_015, 12);

        assertThat(trades())
                .extracting(Trade::price, Trade::quantity)
                .containsExactly(tuple(10_000L, 5L), tuple(10_010L, 5L));
        assertThat(book.bestBid()).isEqualTo(10_015);
        assertThat(book.leavesQuantity(4)).isEqualTo(2);
        assertThat(book.bestAsk()).isEqualTo(10_020);
    }

    @Test
    void samePriceFillsOldestFirst() {
        limit(1, BUY, 10_000, 5);
        limit(2, BUY, 10_000, 5);
        limit(3, BUY, 10_000, 5);
        limit(4, SELL, 10_000, 7);

        assertThat(trades()).extracting(Trade::buyOrderId).containsExactly(1L, 2L);
        assertThat(book.depth(BUY, 5)).containsExactly(new DepthLevel(10_000, 8, 2));
    }

    @Test
    void betterPriceBeatsEarlierTime() {
        limit(1, BUY, 10_000, 5);
        limit(2, BUY, 10_005, 5);
        limit(3, SELL, 10_000, 5);

        assertThat(trades()).extracting(Trade::buyOrderId, Trade::price).containsExactly(tuple(2L, 10_005L));
    }

    @Test
    void depthIsBestFirstAndLimited() {
        limit(1, BUY, 9_990, 1);
        limit(1, BUY, 10_000, 2);
        limit(1, BUY, 9_995, 3);

        assertThat(book.depth(BUY, 2)).containsExactly(new DepthLevel(10_000, 2, 1), new DepthLevel(9_995, 3, 1));
    }

    @Test
    void rejectsInvalidOrdersWithOneReason() {
        submit(new NewOrder("c-1", 1, "NOPE", BUY, OrderType.LIMIT, 10_000, 1));
        submit(new NewOrder("c-2", 1, ABC, BUY, OrderType.LIMIT, 10_000, 0));
        submit(new NewOrder("c-3", 1, ABC, BUY, OrderType.LIMIT, 10_000, 1_000_001));
        submit(new NewOrder("c-4", 1, ABC, BUY, OrderType.LIMIT, 0, 1));
        submit(new NewOrder("c-5", 1, ABC, BUY, OrderType.LIMIT, -5, 1));
        submit(new NewOrder("c-6", 1, ABC, BUY, OrderType.LIMIT, 10_001, 1));
        submit(new NewOrder("c-7", 1, ABC, BUY, OrderType.MARKET, 10_000, 1));

        assertThat(events)
                .extracting(e -> ((OrderRejected) e).reason())
                .containsExactly(
                        RejectReason.UNKNOWN_SYMBOL,
                        RejectReason.INVALID_QUANTITY,
                        RejectReason.INVALID_QUANTITY,
                        RejectReason.INVALID_PRICE,
                        RejectReason.INVALID_PRICE,
                        RejectReason.PRICE_NOT_ON_TICK,
                        RejectReason.INVALID_PRICE);
        assertThat(events).extracting(e -> ((OrderRejected) e).orderId()).containsOnly(0L);
        assertThat(book.orderCount()).isZero();
    }

    @Test
    void rejectsDoNotUseOrderIds() {
        submit(new NewOrder("c-1", 1, ABC, BUY, OrderType.LIMIT, 10_001, 1));
        limit(1, BUY, 10_000, 1);

        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        OrderAccepted.class, a -> assertThat(a.orderId()).isEqualTo(1));
    }

    @Test
    void eventsAreNumberedInOrderAndStampedWithSimTime() {
        limit(1, SELL, 10_000, 1);
        engine.apply(new ClockTick(T0 + 42));
        engine.apply(new NewOrder("c-2", 2, ABC, BUY, OrderType.LIMIT, 10_000, 1));

        assertThat(events).extracting(ExchangeEvent::seq).containsExactly(2L, 3L, 4L);
        assertThat(events).extracting(ExchangeEvent::simTime).containsExactly(T0, T0 + 42, T0 + 42);
    }

    private void limit(long account, Side side, long price, long quantity) {
        submit(new NewOrder("c-" + account, account, ABC, side, OrderType.LIMIT, price, quantity));
    }

    private void submit(NewOrder order) {
        engine.apply(order);
    }

    private List<Trade> trades() {
        return events.stream()
                .filter(Trade.class::isInstance)
                .map(Trade.class::cast)
                .toList();
    }
}
