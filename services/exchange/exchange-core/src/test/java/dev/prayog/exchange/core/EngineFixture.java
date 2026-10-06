package dev.prayog.exchange.core;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import java.util.ArrayList;
import java.util.List;

/**
 * An engine with one instrument (ABC: tick 5 paise, reference ₹100.00, 20% band → ₹80.00–₹120.00), clock at
 * {@link #T0}, market OPEN, and every later event collected. The opening event itself is dropped so each test sees
 * only its own events (sequence numbers therefore start at 2).
 */
final class EngineFixture {

    static final long T0 = 1_790_000_000_000_000L;
    static final String ABC = "ABC";
    static final Instrument INSTRUMENT = new Instrument(ABC, 5, 1_000_000, 10_000, 20);

    final List<ExchangeEvent> events = new ArrayList<>();
    final MatchingEngine engine = new MatchingEngine(List.of(INSTRUMENT), events::add);
    final OrderBook book = engine.book(ABC);
    private int nextClientId = 1; // every order gets its own client order ID, as real clients do

    EngineFixture() {
        engine.apply(new ClockTick(T0));
        engine.apply(new SetRules(MatchingEngine.LATEST_RULES));
        engine.apply(new SetSessionState(SessionState.OPEN));
        events.clear();
    }

    void apply(Command command) {
        engine.apply(command);
    }

    /** Places a limit order and returns its order ID (0 if rejected). */
    long limit(long account, Side side, long price, long quantity) {
        return place(new NewOrder("c-" + nextClientId++, account, ABC, side, OrderType.LIMIT, price, quantity));
    }

    long market(long account, Side side, long quantity) {
        return place(new NewOrder("m-" + nextClientId++, account, ABC, side, OrderType.MARKET, 0, quantity));
    }

    void cancel(long account, long orderId) {
        apply(new CancelOrder("x-" + orderId, account, ABC, orderId));
    }

    void modify(long account, long orderId, long price, long quantity) {
        apply(new ModifyOrder("x-" + orderId, account, ABC, orderId, price, quantity));
    }

    /** Events produced after {@code mark} (an earlier {@code events.size()}). */
    List<ExchangeEvent> since(int mark) {
        return List.copyOf(events.subList(mark, events.size()));
    }

    <T extends ExchangeEvent> List<T> all(Class<T> type) {
        return events.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private long place(NewOrder order) {
        int mark = events.size();
        apply(order);
        return since(mark).stream()
                .filter(OrderAccepted.class::isInstance)
                .map(e -> ((OrderAccepted) e).orderId())
                .findFirst()
                .orElse(0L);
    }
}
