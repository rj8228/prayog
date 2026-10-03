package dev.prayog.exchange.core;

import static dev.prayog.contracts.Side.BUY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.SessionStateChanged;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The engine opens and closes the market itself when clock ticks cross the scheduled times (IST). */
class SessionScheduleTest {

    private static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    private static final SessionSchedule NSE_HOURS =
            new SessionSchedule(LocalTime.of(9, 15), LocalTime.of(15, 30), IST);

    private final List<ExchangeEvent> events = new ArrayList<>();
    private final MatchingEngine engine = new MatchingEngine(List.of(EngineFixture.INSTRUMENT), NSE_HOURS, events::add);

    /** Sim time (epoch microseconds) for a wall-clock time in IST. */
    private static long at(LocalDate day, int hour, int minute, int second) {
        return day.atTime(hour, minute, second).toEpochSecond(IST) * 1_000_000L;
    }

    private void tick(LocalDate day, int hour, int minute, int second) {
        engine.apply(new ClockTick(at(day, hour, minute, second)));
    }

    private List<SessionState> states() {
        return events.stream()
                .filter(SessionStateChanged.class::isInstance)
                .map(e -> ((SessionStateChanged) e).state())
                .toList();
    }

    private void restingOrder() {
        engine.apply(new NewOrder("c-1", 1, EngineFixture.ABC, BUY, OrderType.LIMIT, 10_000, 5));
    }

    @Test
    void opensWhenATickReachesTheOpeningTime() {
        tick(MONDAY, 9, 14, 59);
        assertThat(events).isEmpty();
        tick(MONDAY, 9, 15, 0);

        assertThat(events).containsExactly(new SessionStateChanged(1, at(MONDAY, 9, 15, 0), SessionState.OPEN));
    }

    @Test
    void firstTickInsideTradingHoursOpens() {
        tick(MONDAY, 11, 0, 0);
        assertThat(states()).containsExactly(SessionState.OPEN);
    }

    @Test
    void closesAtTheCloseAndExpiresOrders() {
        tick(MONDAY, 9, 15, 0);
        restingOrder();
        tick(MONDAY, 15, 30, 0);

        assertThat(states()).containsExactly(SessionState.OPEN, SessionState.CLOSED);
        assertThat(events.getLast())
                .isInstanceOfSatisfying(
                        OrderCancelled.class, c -> assertThat(c.reason()).isEqualTo(CancelReason.EXPIRED));
    }

    @Test
    void opsHaltLastsUntilTheNextBoundary() {
        tick(MONDAY, 9, 15, 0);
        engine.apply(new SetSessionState(SessionState.HALTED));
        tick(MONDAY, 12, 0, 0);
        assertThat(states()).containsExactly(SessionState.OPEN, SessionState.HALTED);

        tick(MONDAY, 15, 30, 0);
        assertThat(states()).containsExactly(SessionState.OPEN, SessionState.HALTED, SessionState.CLOSED);
    }

    @Test
    void earlyManualOpenIsNotResetAtTheScheduledOpen() {
        tick(MONDAY, 9, 0, 0);
        engine.apply(new SetSessionState(SessionState.OPEN));
        restingOrder();
        int before = events.size();
        tick(MONDAY, 9, 15, 0);

        assertThat(events).hasSize(before); // no close, no expiry, no second open
    }

    @Test
    void aTickThatSkipsTheNightClosesThenOpens() {
        tick(MONDAY, 14, 0, 0);
        restingOrder();
        tick(MONDAY.plusDays(1), 10, 0, 0);

        assertThat(states()).containsExactly(SessionState.OPEN, SessionState.CLOSED, SessionState.OPEN);
        assertThat(events).filteredOn(OrderCancelled.class::isInstance).hasSize(1);
    }

    @Test
    void openMustBeBeforeClose() {
        assertThatThrownBy(() -> new SessionSchedule(LocalTime.of(15, 30), LocalTime.of(9, 15), IST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
