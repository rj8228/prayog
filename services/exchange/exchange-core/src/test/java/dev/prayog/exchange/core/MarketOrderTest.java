package dev.prayog.exchange.core;

import static dev.prayog.contracts.Side.BUY;
import static dev.prayog.contracts.Side.SELL;
import static dev.prayog.exchange.core.EngineFixture.ABC;
import static dev.prayog.exchange.core.EngineFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class MarketOrderTest {

    private final EngineFixture f = new EngineFixture();

    @AfterEach
    void bookStaysConsistent() {
        f.book.checkInvariants();
    }

    @Test
    void marketBuySweepsBestPricesFirst() {
        f.limit(1, SELL, 10_010, 5);
        f.limit(2, SELL, 10_000, 5);
        int mark = f.events.size();
        long id = f.market(3, BUY, 7);

        assertThat(f.since(mark).getFirst())
                .isEqualTo(new OrderAccepted(3, T0, id, "m-3", 3, ABC, BUY, OrderType.MARKET, 0, 7));
        assertThat(f.all(Trade.class))
                .extracting(Trade::price, Trade::quantity)
                .containsExactly(tuple(10_000L, 5L), tuple(10_010L, 2L));
        assertThat(f.all(OrderCancelled.class)).isEmpty();
        assertThat(f.book.bestBid()).isZero();
        assertThat(f.book.leavesQuantity(1)).isEqualTo(3);
    }

    @Test
    void marketSellHitsHighestBidFirst() {
        f.limit(1, BUY, 9_990, 5);
        f.limit(2, BUY, 10_000, 5);
        f.market(3, SELL, 6);

        assertThat(f.all(Trade.class))
                .extracting(Trade::buyOrderId, Trade::price, Trade::quantity)
                .containsExactly(tuple(2L, 10_000L, 5L), tuple(1L, 9_990L, 1L));
    }

    @Test
    void unfilledRemainderIsCancelledForNoLiquidity() {
        f.limit(1, SELL, 10_000, 3);
        int mark = f.events.size();
        long id = f.market(2, BUY, 10);

        assertThat(f.since(mark).getLast())
                .isEqualTo(new OrderCancelled(4, T0, id, 2, ABC, 7, CancelReason.NO_LIQUIDITY));
        assertThat(f.book.orderCount()).isZero();
    }

    @Test
    void marketOrderOnEmptyBookIsAcceptedThenCancelled() {
        long id = f.market(1, SELL, 10);

        assertThat(f.events)
                .containsExactly(
                        new OrderAccepted(1, T0, id, "m-1", 1, ABC, SELL, OrderType.MARKET, 0, 10),
                        new OrderCancelled(2, T0, id, 1, ABC, 10, CancelReason.NO_LIQUIDITY));
    }

    @Test
    void marketOrderNeverRests() {
        f.limit(1, SELL, 10_000, 2);
        f.market(2, BUY, 5);

        assertThat(f.book.bestBid()).isZero();
        assertThat(f.book.orderCount()).isZero();
    }
}
