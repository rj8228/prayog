package dev.prayog.exchange.core;

import static dev.prayog.contracts.Side.BUY;
import static dev.prayog.contracts.Side.SELL;
import static dev.prayog.exchange.core.EngineFixture.ABC;
import static dev.prayog.exchange.core.EngineFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** S6 rules: price bands, sessions, self-trade prevention, DAY expiry and the account kill switch. */
class ExchangeRulesTest {

    private final EngineFixture f = new EngineFixture();

    @AfterEach
    void bookStaysConsistent() {
        f.book.checkInvariants();
    }

    private RejectReason lastReject() {
        return ((OrderRejected) f.events.getLast()).reason();
    }

    @Nested
    class PriceBand {

        @Test
        void bandEdgesAreRoundedInwardToTheTick() {
            Instrument odd = new Instrument("ODD", 5, 100, 10_003, 10); // 90.027 .. 110.033 rupees
            assertThat(odd.bandLow()).isEqualTo(9_005);
            assertThat(odd.bandHigh()).isEqualTo(11_000);
            assertThat(EngineFixture.INSTRUMENT.bandLow()).isEqualTo(8_000);
            assertThat(EngineFixture.INSTRUMENT.bandHigh()).isEqualTo(12_000);
        }

        @Test
        void limitPricesOnTheEdgeAreAccepted() {
            assertThat(f.limit(1, BUY, 8_000, 1)).isPositive();
            assertThat(f.limit(1, SELL, 12_000, 1)).isPositive();
        }

        @Test
        void limitPricesOutsideTheBandAreRejected() {
            f.limit(1, BUY, 7_995, 1);
            assertThat(lastReject()).isEqualTo(RejectReason.PRICE_OUTSIDE_BAND);
            f.limit(1, SELL, 12_005, 1);
            assertThat(lastReject()).isEqualTo(RejectReason.PRICE_OUTSIDE_BAND);
        }

        @Test
        void modifyOutsideTheBandIsRejected() {
            long id = f.limit(1, BUY, 10_000, 1);
            f.modify(1, id, 12_005, 1);

            assertThat(lastReject()).isEqualTo(RejectReason.PRICE_OUTSIDE_BAND);
            assertThat(f.book.leavesQuantity(id)).isEqualTo(1);
        }

        @Test
        void tickIsCheckedBeforeBand() {
            f.limit(1, BUY, 12_001, 1);
            assertThat(lastReject()).isEqualTo(RejectReason.PRICE_NOT_ON_TICK);
        }

        @Test
        void invalidBandSettingsAreRefused() {
            assertThatThrownBy(() -> new Instrument("X", 5, 100, 0, 10)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Instrument("X", 5, 100, 10_000, 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Instrument("X", 5, 100, 10_000, 100))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> new Instrument("X", 5, 100, 4, 10)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Sessions {

        @Test
        void engineStartsClosedAndRejectsOrders() {
            List<ExchangeEvent> events = new ArrayList<>();
            MatchingEngine engine = new MatchingEngine(List.of(EngineFixture.INSTRUMENT), events::add);
            engine.apply(new NewOrder("c-1", 1, ABC, BUY, OrderType.LIMIT, 10_000, 1));

            assertThat(events)
                    .singleElement()
                    .isInstanceOfSatisfying(
                            OrderRejected.class, r -> assertThat(r.reason()).isEqualTo(RejectReason.SESSION_NOT_OPEN));
        }

        @Test
        void stateChangesAreAnnouncedOnceEach() {
            f.apply(new SetSessionState(SessionState.HALTED));
            f.apply(new SetSessionState(SessionState.HALTED)); // no change, no event
            f.apply(new SetSessionState(SessionState.OPEN));

            assertThat(f.events)
                    .containsExactly(
                            new SessionStateChanged(2, T0, SessionState.HALTED),
                            new SessionStateChanged(3, T0, SessionState.OPEN));
        }

        @Test
        void haltedAcceptsCancelsOnly() {
            long id = f.limit(1, BUY, 10_000, 5);
            f.apply(new SetSessionState(SessionState.HALTED));

            f.limit(2, SELL, 10_000, 5);
            assertThat(lastReject()).isEqualTo(RejectReason.SESSION_NOT_OPEN);
            f.modify(1, id, 10_000, 3);
            assertThat(lastReject()).isEqualTo(RejectReason.SESSION_NOT_OPEN);
            f.cancel(1, id);
            assertThat(f.events.getLast()).isInstanceOf(OrderCancelled.class);
        }

        @Test
        void haltKeepsTheBookAndResumeTradesAgainstIt() {
            long id = f.limit(1, BUY, 10_000, 5);
            f.apply(new SetSessionState(SessionState.HALTED));
            f.apply(new SetSessionState(SessionState.OPEN));
            f.limit(2, SELL, 10_000, 5);

            assertThat(f.all(Trade.class)).extracting(Trade::buyOrderId).containsExactly(id);
        }

        @Test
        void closingExpiresEveryLiveOrderInSymbolThenIdOrder() {
            List<ExchangeEvent> events = new ArrayList<>();
            Instrument xyz = new Instrument("XYZ", 5, 100, 10_000, 20);
            MatchingEngine engine = new MatchingEngine(List.of(xyz, EngineFixture.INSTRUMENT), events::add);
            engine.apply(new ClockTick(T0));
            engine.apply(new SetSessionState(SessionState.OPEN));
            engine.apply(new NewOrder("a", 1, "XYZ", BUY, OrderType.LIMIT, 10_000, 1)); // order 1
            engine.apply(new NewOrder("b", 2, ABC, SELL, OrderType.LIMIT, 10_100, 2)); // order 2
            engine.apply(new NewOrder("c", 3, ABC, BUY, OrderType.LIMIT, 9_900, 3)); // order 3
            events.clear();
            engine.apply(new SetSessionState(SessionState.CLOSED));

            assertThat(events.getFirst()).isEqualTo(new SessionStateChanged(5, T0, SessionState.CLOSED));
            assertThat(events.subList(1, events.size()))
                    .extracting(e -> ((OrderCancelled) e).orderId(), e -> ((OrderCancelled) e).reason())
                    .containsExactly(
                            tuple(2L, CancelReason.EXPIRED), // ABC sorts before XYZ
                            tuple(3L, CancelReason.EXPIRED),
                            tuple(1L, CancelReason.EXPIRED));
            assertThat(engine.book(ABC).orderCount()).isZero();
            assertThat(engine.book("XYZ").orderCount()).isZero();
        }

        @Test
        void closedRejectsCancelsToo() {
            f.apply(new SetSessionState(SessionState.CLOSED));
            f.cancel(1, 1);

            assertThat(lastReject()).isEqualTo(RejectReason.SESSION_NOT_OPEN);
        }
    }

    @Nested
    class SelfTradePrevention {

        @Test
        void incomingOrderIsCancelledInsteadOfTradingWithItself() {
            long resting = f.limit(1, SELL, 10_000, 5);
            int mark = f.events.size();
            long incoming = f.limit(1, BUY, 10_000, 5);

            assertThat(f.since(mark).getLast())
                    .isEqualTo(new OrderCancelled(4, T0, incoming, 1, ABC, 5, CancelReason.SELF_TRADE_PREVENTION));
            assertThat(f.all(Trade.class)).isEmpty();
            assertThat(f.book.leavesQuantity(resting)).isEqualTo(5);
            assertThat(f.book.bestBid()).isZero();
        }

        @Test
        void tradesWithOthersBeforeTheOwnOrderStand() {
            f.limit(2, SELL, 10_000, 3);
            f.limit(1, SELL, 10_005, 3);
            f.limit(3, SELL, 10_010, 3);
            long incoming = f.limit(1, BUY, 10_010, 9);

            assertThat(f.all(Trade.class))
                    .extracting(Trade::sellAccountId, Trade::quantity)
                    .containsExactly(tuple(2L, 3L));
            assertThat(f.events.getLast())
                    .isEqualTo(new OrderCancelled(7, T0, incoming, 1, ABC, 6, CancelReason.SELF_TRADE_PREVENTION));
            assertThat(f.book.bestAsk()).isEqualTo(10_005);
        }

        @Test
        void nonCrossingOwnOrdersMayRestOnBothSides() {
            f.limit(1, SELL, 10_010, 5);
            f.limit(1, BUY, 10_000, 5);

            assertThat(f.book.bestBid()).isEqualTo(10_000);
            assertThat(f.book.bestAsk()).isEqualTo(10_010);
        }

        @Test
        void appliesToMarketOrders() {
            f.limit(1, SELL, 10_000, 5);
            long incoming = f.market(1, BUY, 5);

            assertThat(f.events.getLast())
                    .isEqualTo(new OrderCancelled(4, T0, incoming, 1, ABC, 5, CancelReason.SELF_TRADE_PREVENTION));
        }

        @Test
        void appliesToAModifyThatCrosses() {
            f.limit(1, SELL, 10_010, 5);
            long bid = f.limit(1, BUY, 10_000, 5);
            f.modify(1, bid, 10_010, 5);

            assertThat(f.events.getLast())
                    .isEqualTo(new OrderCancelled(5, T0, bid, 1, ABC, 5, CancelReason.SELF_TRADE_PREVENTION));
            assertThat(f.book.leavesQuantity(bid)).isZero();
        }
    }

    @Nested
    class MarketOrdersAndBands {

        @Test
        void marketOrderStopsAtTheBandEdge() {
            Instrument tight = new Instrument("TGT", 5, 100, 10_000, 1); // band 99.00 .. 101.00
            List<ExchangeEvent> events = new ArrayList<>();
            MatchingEngine engine = new MatchingEngine(List.of(tight), events::add);
            engine.apply(new ClockTick(T0));
            engine.apply(new SetSessionState(SessionState.OPEN));
            engine.apply(new NewOrder("s", 2, "TGT", SELL, OrderType.LIMIT, 10_100, 5));
            engine.apply(new NewOrder("m", 1, "TGT", BUY, OrderType.MARKET, 0, 8));

            assertThat(events).filteredOn(Trade.class::isInstance).hasSize(1);
            assertThat(events.getLast()).isInstanceOfSatisfying(OrderCancelled.class, c -> {
                assertThat(c.cancelledQuantity()).isEqualTo(3);
                assertThat(c.reason()).isEqualTo(CancelReason.NO_LIQUIDITY);
            });
        }
    }

    @Nested
    class KillSwitch {

        @Test
        void disablingCancelsEveryLiveOrderOfThatAccountOnly() {
            long a = f.limit(1, BUY, 9_990, 5);
            long other = f.limit(2, BUY, 9_995, 5);
            long b = f.limit(1, SELL, 10_010, 5);
            int mark = f.events.size();
            f.apply(new SetAccountEnabled(1, false));

            assertThat(f.since(mark))
                    .containsExactly(
                            new OrderCancelled(5, T0, a, 1, ABC, 5, CancelReason.KILL_SWITCH),
                            new OrderCancelled(6, T0, b, 1, ABC, 5, CancelReason.KILL_SWITCH));
            assertThat(f.book.leavesQuantity(other)).isEqualTo(5);
        }

        @Test
        void disabledAccountIsRejectedUntilReEnabled() {
            f.apply(new SetAccountEnabled(1, false));
            f.limit(1, BUY, 10_000, 5);
            assertThat(lastReject()).isEqualTo(RejectReason.ACCOUNT_DISABLED);

            f.apply(new SetAccountEnabled(1, true));
            assertThat(f.limit(1, BUY, 10_000, 5)).isPositive();
        }

        @Test
        void accountCheckComesBeforeSessionCheck() {
            f.apply(new SetSessionState(SessionState.HALTED));
            f.apply(new SetAccountEnabled(1, false));
            f.limit(1, BUY, 10_000, 5);

            assertThat(lastReject()).isEqualTo(RejectReason.ACCOUNT_DISABLED);
        }
    }
}
