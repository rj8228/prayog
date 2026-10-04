package dev.prayog.exchange.app.account;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * The private feed: each account sees its own order updates and fills, as they happen. This is how a bot learns that
 * a resting order filled while it was busy elsewhere.
 */
public final class AccountHub {

    /** A message on an account's private feed. */
    public sealed interface AccountMessage permits OrderUpdate, Fill {}

    /**
     * An order changed. {@code status} is {@code accepted}, {@code rejected}, {@code cancelled} or {@code modified}.
     * Fields that the event does not carry are null.
     */
    public record OrderUpdate(
            String type,
            String status,
            long eventSeq,
            long simTime,
            long orderId,
            String clientOrderId,
            String symbol,
            Side side,
            OrderType orderType,
            Long price,
            Long quantity,
            Long leavesQuantity,
            String reason)
            implements AccountMessage {}

    /** One of the account's orders traded. {@code side} is this account's side of the trade. */
    public record Fill(
            String type,
            long eventSeq,
            long simTime,
            long tradeId,
            long orderId,
            String symbol,
            Side side,
            long price,
            long quantity,
            boolean aggressor)
            implements AccountMessage {}

    private final Map<Long, List<Sinks.Many<AccountMessage>>> subscribers = new ConcurrentHashMap<>();
    private final int buffer;

    public AccountHub(int buffer) {
        this.buffer = buffer;
    }

    /** Live updates for one account. Disconnected if it falls {@code buffer} messages behind. */
    public Flux<AccountMessage> subscribe(long accountId) {
        Sinks.Many<AccountMessage> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(Queues.<AccountMessage>get(buffer).get());
        subscribers
                .computeIfAbsent(accountId, k -> new CopyOnWriteArrayList<>())
                .add(sink);
        return sink.asFlux().doFinally(signal -> {
            List<Sinks.Many<AccountMessage>> list = subscribers.get(accountId);
            if (list != null) {
                list.remove(sink);
            }
        });
    }

    /** Routes one command's events to the accounts they concern. Called by the outbound stage only. */
    public void publish(List<ExchangeEvent> events) {
        if (subscribers.isEmpty()) {
            return;
        }
        for (ExchangeEvent event : events) {
            switch (event) {
                case OrderAccepted a ->
                    send(
                            a.accountId(),
                            new OrderUpdate(
                                    "order",
                                    "accepted",
                                    a.seq(),
                                    a.simTime(),
                                    a.orderId(),
                                    a.clientOrderId(),
                                    a.symbol(),
                                    a.side(),
                                    a.orderType(),
                                    a.price(),
                                    a.quantity(),
                                    a.quantity(),
                                    null));
                case OrderRejected r ->
                    send(
                            r.accountId(),
                            new OrderUpdate(
                                    "order",
                                    "rejected",
                                    r.seq(),
                                    r.simTime(),
                                    r.orderId(),
                                    r.clientOrderId(),
                                    r.symbol(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    null,
                                    r.reason().name()));
                case OrderCancelled c ->
                    send(
                            c.accountId(),
                            new OrderUpdate(
                                    "order",
                                    "cancelled",
                                    c.seq(),
                                    c.simTime(),
                                    c.orderId(),
                                    null,
                                    c.symbol(),
                                    null,
                                    null,
                                    null,
                                    c.cancelledQuantity(),
                                    0L,
                                    c.reason().name()));
                case OrderModified m ->
                    send(
                            m.accountId(),
                            new OrderUpdate(
                                    "order",
                                    "modified",
                                    m.seq(),
                                    m.simTime(),
                                    m.orderId(),
                                    null,
                                    m.symbol(),
                                    null,
                                    null,
                                    m.price(),
                                    m.quantity(),
                                    m.leavesQuantity(),
                                    null));
                case Trade t -> {
                    boolean buyerAggressed = t.aggressorSide() == Side.BUY;
                    send(
                            t.buyAccountId(),
                            new Fill(
                                    "fill",
                                    t.seq(),
                                    t.simTime(),
                                    t.tradeId(),
                                    t.buyOrderId(),
                                    t.symbol(),
                                    Side.BUY,
                                    t.price(),
                                    t.quantity(),
                                    buyerAggressed));
                    send(
                            t.sellAccountId(),
                            new Fill(
                                    "fill",
                                    t.seq(),
                                    t.simTime(),
                                    t.tradeId(),
                                    t.sellOrderId(),
                                    t.symbol(),
                                    Side.SELL,
                                    t.price(),
                                    t.quantity(),
                                    !buyerAggressed));
                }
                case SessionStateChanged s -> {
                    // public: on the market-data feed
                }
                case BookUpdate b -> {
                    // not emitted by the engine
                }
            }
        }
    }

    public int subscriberCount() {
        return subscribers.values().stream().mapToInt(List::size).sum();
    }

    private void send(long accountId, AccountMessage message) {
        List<Sinks.Many<AccountMessage>> list = subscribers.get(accountId);
        if (list == null) {
            return;
        }
        for (Sinks.Many<AccountMessage> sink : list) {
            if (sink.tryEmitNext(message).isFailure()) {
                list.remove(sink);
                sink.tryEmitError(new IllegalStateException("private-feed subscriber too slow"));
            }
        }
    }
}
