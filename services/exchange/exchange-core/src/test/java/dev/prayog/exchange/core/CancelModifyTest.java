package dev.prayog.exchange.core;

import static dev.prayog.contracts.Side.BUY;
import static dev.prayog.contracts.Side.SELL;
import static dev.prayog.exchange.core.EngineFixture.ABC;
import static dev.prayog.exchange.core.EngineFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CancelModifyTest {

    private final EngineFixture f = new EngineFixture();

    @AfterEach
    void bookStaysConsistent() {
        f.book.checkInvariants();
    }

    private ExchangeEvent last() {
        return f.events.getLast();
    }

    @Nested
    class Cancel {

        @Test
        void removesTheOpenPartOnly() {
            long id = f.limit(1, BUY, 10_000, 10);
            f.limit(2, SELL, 10_000, 4);
            f.cancel(1, id);

            assertThat(last()).isEqualTo(new OrderCancelled(4, T0, id, 1, ABC, 6, CancelReason.CLIENT_REQUEST));
            assertThat(f.book.orderCount()).isZero();
        }

        @Test
        void fromTheMiddleOfAQueueKeepsTheOthersInOrder() {
            long a = f.limit(1, BUY, 10_000, 1);
            long b = f.limit(2, BUY, 10_000, 2);
            long c = f.limit(3, BUY, 10_000, 3);
            f.cancel(2, b);
            f.limit(4, SELL, 10_000, 10);

            assertThat(f.all(Trade.class)).extracting(Trade::buyOrderId).containsExactly(a, c);
        }

        @Test
        void unknownOrderIsRejected() {
            f.cancel(1, 99);

            assertThat(last()).isEqualTo(new OrderRejected(1, T0, 99, "x-99", 1, ABC, RejectReason.UNKNOWN_ORDER));
        }

        @Test
        void anotherAccountsOrderLooksUnknown() {
            long id = f.limit(1, BUY, 10_000, 10);
            f.cancel(2, id);

            assertThat(last())
                    .isInstanceOfSatisfying(
                            OrderRejected.class, r -> assertThat(r.reason()).isEqualTo(RejectReason.UNKNOWN_ORDER));
            assertThat(f.book.leavesQuantity(id)).isEqualTo(10);
        }

        @Test
        void tooLateAfterAFullFill() {
            long id = f.limit(1, BUY, 10_000, 5);
            f.limit(2, SELL, 10_000, 5);
            f.cancel(1, id);

            assertThat(last())
                    .isInstanceOfSatisfying(
                            OrderRejected.class, r -> assertThat(r.reason()).isEqualTo(RejectReason.UNKNOWN_ORDER));
        }

        @Test
        void unknownSymbolIsRejected() {
            f.apply(new CancelOrder("x-1", 1, "NOPE", 1));

            assertThat(last())
                    .isInstanceOfSatisfying(
                            OrderRejected.class, r -> assertThat(r.reason()).isEqualTo(RejectReason.UNKNOWN_SYMBOL));
        }
    }

    @Nested
    class Modify {

        @Test
        void reducingQuantityKeepsQueuePosition() {
            long a = f.limit(1, BUY, 10_000, 5);
            long b = f.limit(2, BUY, 10_000, 5);
            f.modify(1, a, 10_000, 3);
            f.limit(3, SELL, 10_000, 4);

            assertThat(f.all(OrderModified.class)).containsExactly(new OrderModified(3, T0, a, 1, ABC, 10_000, 3, 3));
            assertThat(f.all(Trade.class))
                    .extracting(Trade::buyOrderId, Trade::quantity)
                    .containsExactly(tuple(a, 3L), tuple(b, 1L));
        }

        @Test
        void quantityIsTheNewTotalIncludingFills() {
            long a = f.limit(1, BUY, 10_000, 10);
            f.limit(2, SELL, 10_000, 4);
            f.modify(1, a, 10_000, 8);

            assertThat(last()).isEqualTo(new OrderModified(4, T0, a, 1, ABC, 10_000, 8, 4));
            assertThat(f.book.leavesQuantity(a)).isEqualTo(4);
        }

        @Test
        void increasingQuantityLosesPriority() {
            long a = f.limit(1, BUY, 10_000, 5);
            long b = f.limit(2, BUY, 10_000, 5);
            f.modify(1, a, 10_000, 6);
            f.limit(3, SELL, 10_000, 5);

            assertThat(f.all(Trade.class)).extracting(Trade::buyOrderId).containsExactly(b);
            assertThat(f.book.leavesQuantity(a)).isEqualTo(6);
        }

        @Test
        void changingPriceLosesPriority() {
            long a = f.limit(1, BUY, 9_995, 5);
            long b = f.limit(2, BUY, 10_000, 5);
            f.modify(1, a, 10_000, 5);
            f.limit(3, SELL, 10_000, 5);

            assertThat(f.all(Trade.class)).extracting(Trade::buyOrderId).containsExactly(b);
            assertThat(f.book.depth(BUY, 5)).containsExactly(new DepthLevel(10_000, 5, 1));
        }

        @Test
        void newPriceThatCrossesTradesImmediately() {
            long a = f.limit(1, BUY, 10_000, 10);
            long ask = f.limit(2, SELL, 10_010, 4);
            int mark = f.events.size();
            f.modify(1, a, 10_010, 10);

            assertThat(f.since(mark))
                    .containsExactly(
                            new OrderModified(3, T0, a, 1, ABC, 10_010, 10, 10),
                            new Trade(4, T0, 1, ABC, 10_010, 4, BUY, a, ask, 1, 2));
            assertThat(f.book.leavesQuantity(a)).isEqualTo(6);
            assertThat(f.book.bestBid()).isEqualTo(10_010);
        }

        @Test
        void reducingToTheFilledAmountOrBelowCancels() {
            long a = f.limit(1, BUY, 10_000, 10);
            f.limit(2, SELL, 10_000, 4);
            f.modify(1, a, 10_000, 4);

            assertThat(last()).isEqualTo(new OrderCancelled(4, T0, a, 1, ABC, 6, CancelReason.MODIFIED_TO_ZERO));
            assertThat(f.book.orderCount()).isZero();
        }

        @Test
        void sameValuesIsAcceptedAndKeepsPriority() {
            long a = f.limit(1, BUY, 10_000, 5);
            long b = f.limit(2, BUY, 10_000, 5);
            f.modify(1, a, 10_000, 5);
            f.limit(3, SELL, 10_000, 5);

            assertThat(f.all(Trade.class)).extracting(Trade::buyOrderId).containsExactly(a);
            assertThat(b).isPositive();
        }

        @Test
        void anotherAccountsOrderLooksUnknown() {
            long a = f.limit(1, BUY, 10_000, 5);
            f.modify(2, a, 10_000, 1);

            assertThat(last()).isEqualTo(new OrderRejected(2, T0, a, "x-" + a, 2, ABC, RejectReason.UNKNOWN_ORDER));
            assertThat(f.book.leavesQuantity(a)).isEqualTo(5);
        }

        @Test
        void invalidValuesAreRejectedAndTheOrderIsUntouched() {
            long a = f.limit(1, BUY, 10_000, 5);
            f.modify(1, a, 10_000, 0);
            f.modify(1, a, 10_000, 1_000_001);
            f.modify(1, a, 0, 5);
            f.modify(1, a, 10_001, 5);

            assertThat(f.all(OrderRejected.class))
                    .extracting(OrderRejected::reason)
                    .containsExactly(
                            RejectReason.INVALID_QUANTITY,
                            RejectReason.INVALID_QUANTITY,
                            RejectReason.INVALID_PRICE,
                            RejectReason.PRICE_NOT_ON_TICK);
            assertThat(f.all(OrderRejected.class))
                    .extracting(OrderRejected::orderId)
                    .containsOnly(a);
            assertThat(f.book.depth(BUY, 5)).containsExactly(new DepthLevel(10_000, 5, 1));
        }
    }

    @Test
    void clockNeverGoesBackwards() {
        f.apply(new ClockTick(T0 + 100));
        f.apply(new ClockTick(T0 + 50));
        f.limit(1, BUY, 10_000, 1);

        assertThat(last().simTime()).isEqualTo(T0 + 100);
    }
}
