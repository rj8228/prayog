package dev.prayog.exchange.app.admin;

import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.app.market.MarketMessages.Change;
import dev.prayog.exchange.app.market.MarketMessages.Level;
import dev.prayog.exchange.core.MatchingEngine;
import dev.prayog.exchange.core.journal.EngineSetup;
import dev.prayog.exchange.core.journal.JournalCodec;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.JournalReader;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Read-only views computed from the journal on disk, while the exchange keeps running: the history of one order, and
 * a symbol's book and trades over a time window (for the replay viewer).
 */
public final class JournalViews {

    private final Path dir;

    public JournalViews(Path dir) {
        this.dir = dir;
    }

    /** One step of an order's life. {@code detail} holds the event's own fields. */
    public record JourneyStep(long eventSeq, long simTime, String event, Map<String, Object> detail) {}

    /** Every event that mentions {@code orderId}, oldest first, with the account that owns it. */
    public record Journey(long orderId, long accountId, List<JourneyStep> steps) {}

    public Journey journey(long orderId) throws IOException {
        JournalCodec codec = new JournalCodec();
        List<JourneyStep> steps = new ArrayList<>();
        long[] account = {0};
        JournalReader.read(dir, JournalHandler.EVENTS, (seq, buffer, offset, length) -> {
            ExchangeEvent event = codec.decodeEvent(buffer, offset);
            switch (event) {
                case OrderAccepted a
                when a.orderId() == orderId -> {
                    account[0] = a.accountId();
                    steps.add(step(
                            a,
                            "accepted",
                            Map.of(
                                    "side",
                                    a.side(),
                                    "type",
                                    a.orderType(),
                                    "price",
                                    a.price(),
                                    "quantity",
                                    a.quantity(),
                                    "clientOrderId",
                                    a.clientOrderId())));
                }
                case Trade t
                when t.buyOrderId() == orderId || t.sellOrderId() == orderId ->
                    steps.add(step(
                            t,
                            "trade",
                            Map.of(
                                    "tradeId",
                                    t.tradeId(),
                                    "price",
                                    t.price(),
                                    "quantity",
                                    t.quantity(),
                                    "aggressor",
                                    t.aggressorSide(),
                                    "role",
                                    (t.aggressorSide() == Side.BUY) == (t.buyOrderId() == orderId)
                                            ? "taker"
                                            : "maker")));
                case OrderModified m
                when m.orderId() == orderId ->
                    steps.add(step(
                            m,
                            "modified",
                            Map.of(
                                    "price",
                                    m.price(),
                                    "quantity",
                                    m.quantity(),
                                    "leavesQuantity",
                                    m.leavesQuantity())));
                case OrderCancelled c
                when c.orderId() == orderId ->
                    steps.add(step(
                            c, "cancelled", Map.of("cancelledQuantity", c.cancelledQuantity(), "reason", c.reason())));
                case OrderRejected r
                when r.orderId() == orderId && orderId != 0 ->
                    steps.add(step(r, "change rejected", Map.of("reason", r.reason())));
                default -> {}
            }
        });
        return new Journey(orderId, account[0], steps);
    }

    private static JourneyStep step(ExchangeEvent e, String name, Map<String, Object> detail) {
        return new JourneyStep(e.seq(), e.simTime(), name, detail);
    }

    /** A replayable window: the book when it starts, then each command's changes and trades. */
    public record Window(
            String symbol,
            long fromSimTime,
            long toSimTime,
            List<Level> bids,
            List<Level> asks,
            List<Frame> frames,
            boolean truncated) {}

    public record Frame(long simTime, List<Change> changes, List<TradeView> trades) {}

    public record TradeView(long tradeId, long price, long quantity, Side aggressor) {}

    /**
     * Rebuilds {@code symbol}'s book over [{@code fromSimTime}, {@code toSimTime}] by replaying the input journal into a
     * fresh engine (command boundaries matter: only the state between commands is real). Up to {@code maxFrames}.
     */
    public Window window(String symbol, long fromSimTime, long toSimTime, int maxFrames) throws IOException {
        JournalCodec codec = new JournalCodec();
        OrderTracker tracker = new OrderTracker();
        List<ExchangeEvent> commandEvents = new ArrayList<>();
        MatchingEngine[] engine = {null};
        List<Frame> frames = new ArrayList<>();
        Window[] start = {null};
        boolean[] truncated = {false};
        boolean[] done = {false};
        JournalReader.read(dir, JournalHandler.INPUT, (seq, buffer, offset, length) -> {
            if (engine[0] == null) {
                EngineSetup setup = codec.decodeEngineSetup(buffer, offset);
                engine[0] = setup.newEngine(commandEvents::add);
                return;
            }
            if (truncated[0] || done[0]) {
                return; // no need to replay further
            }
            commandEvents.clear();
            engine[0].apply(codec.decodeCommand(buffer, offset));
            long time = 0;
            List<TradeView> trades = new ArrayList<>();
            for (ExchangeEvent e : commandEvents) {
                tracker.onEvent(e);
                time = e.simTime();
                if (e instanceof Trade t && t.symbol().equals(symbol)) {
                    trades.add(new TradeView(t.tradeId(), t.price(), t.quantity(), t.aggressorSide()));
                }
            }
            List<Change> changes = new ArrayList<>();
            for (OrderTracker.LevelChange c : tracker.endCommand()) {
                if (c.symbol().equals(symbol)) {
                    changes.add(new Change(c.side(), c.price(), c.quantity(), c.orderCount()));
                }
            }
            if (commandEvents.isEmpty() || time < fromSimTime) {
                return;
            }
            if (time > toSimTime) {
                done[0] = true; // past the window
                return;
            }
            if (start[0] == null) {
                start[0] = snapshot(tracker, symbol, time); // the book after this command: frames follow it
                return;
            }
            if (!changes.isEmpty() || !trades.isEmpty()) {
                if (frames.size() >= maxFrames) {
                    truncated[0] = true;
                    return;
                }
                frames.add(new Frame(time, changes, trades));
            }
        });
        if (start[0] == null) {
            return new Window(symbol, fromSimTime, toSimTime, List.of(), List.of(), List.of(), false);
        }
        Window s = start[0];
        return new Window(symbol, s.fromSimTime(), toSimTime, s.bids(), s.asks(), frames, truncated[0]);
    }

    private static Window snapshot(OrderTracker tracker, String symbol, long time) {
        List<Level> bids = new ArrayList<>();
        for (var l : tracker.depth(symbol, Side.BUY, 500)) {
            bids.add(new Level(l.price(), l.quantity(), l.orderCount()));
        }
        List<Level> asks = new ArrayList<>();
        for (var l : tracker.depth(symbol, Side.SELL, 500)) {
            asks.add(new Level(l.price(), l.quantity(), l.orderCount()));
        }
        return new Window(symbol, time, time, bids, asks, List.of(), false);
    }
}
