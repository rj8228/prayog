package dev.prayog.exchange.app.market;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.app.market.MarketMessages.BookDelta;
import dev.prayog.exchange.app.market.MarketMessages.Change;
import dev.prayog.exchange.app.market.MarketMessages.Level;
import dev.prayog.exchange.app.market.MarketMessages.MarketMessage;
import dev.prayog.exchange.app.market.MarketMessages.SessionMessage;
import dev.prayog.exchange.app.market.MarketMessages.Snapshot;
import dev.prayog.exchange.app.market.MarketMessages.Ticker;
import dev.prayog.exchange.app.market.MarketMessages.TradeMessage;
import dev.prayog.exchange.core.DepthLevel;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Market data, derived from the engine's events after they are journaled.
 *
 * <p>One writer (the pipeline's outbound stage) calls {@link #apply}; readers (REST, WebSocket subscriptions) call
 * the query methods. All of it runs under this object's lock, which is what makes "snapshot, then every later delta"
 * exact: a subscriber registers and takes its snapshot inside the same critical section in which deltas are published,
 * so it can neither miss nor double-count a change. The lock is never on the order path: matching and journaling
 * don't wait for it.
 */
public final class MarketHub {

    private static final Logger log = LoggerFactory.getLogger(MarketHub.class);

    private final OrderTracker tracker = new OrderTracker();
    private final Map<String, SymbolState> symbols = new TreeMap<>();
    private final Map<String, Instrument> instruments = new TreeMap<>();
    private final List<Subscriber> subscribers = new CopyOnWriteArrayList<>();
    private final int tradeHistory;
    private final int subscriberBuffer;
    private SessionState session = SessionState.CLOSED;
    private long simTime;

    public MarketHub(int tradeHistory, int subscriberBuffer) {
        this.tradeHistory = tradeHistory;
        this.subscriberBuffer = subscriberBuffer;
    }

    private static final class SymbolState {
        final String symbol;
        long referencePrice;
        long seq;
        final Deque<TradeMessage> trades = new ArrayDeque<>();
        long last;
        long open;
        long high;
        long low;
        long volume;
        long tradeCount;

        SymbolState(String symbol) {
            this.symbol = symbol;
        }
    }

    private record Subscriber(Set<String> symbols, Sinks.Many<MarketMessage> sink) {}

    /** Lists the tradable symbols with their reference prices. */
    public synchronized void setInstruments(Collection<Instrument> instruments) {
        for (Instrument instrument : instruments) {
            this.instruments.put(instrument.symbol(), instrument);
            state(instrument.symbol()).referencePrice = instrument.referencePrice();
        }
    }

    /** Listed instruments, by symbol. */
    public synchronized List<Instrument> instruments() {
        return List.copyOf(instruments.values());
    }

    public synchronized Instrument instrument(String symbol) {
        return instruments.get(symbol);
    }

    /** Applies the events of one command and publishes what changed. Called by the outbound stage only. */
    public synchronized void apply(List<ExchangeEvent> events) {
        List<TradeMessage> trades = new ArrayList<>();
        boolean sessionChanged = false;
        for (ExchangeEvent event : events) {
            tracker.onEvent(event);
            simTime = Math.max(simTime, event.simTime());
            if (event instanceof Trade t) {
                trades.add(recordTrade(t));
            } else if (event instanceof SessionStateChanged s) {
                session = s.state();
                sessionChanged = true;
                if (s.state() == SessionState.OPEN) {
                    symbols.values().forEach(MarketHub::resetSessionStats);
                }
            }
        }
        Map<String, List<Change>> bySymbol = new LinkedHashMap<>();
        for (OrderTracker.LevelChange c : tracker.endCommand()) {
            bySymbol.computeIfAbsent(c.symbol(), k -> new ArrayList<>())
                    .add(new Change(c.side(), c.price(), c.quantity(), c.orderCount()));
        }
        List<BookDelta> deltas = new ArrayList<>();
        bySymbol.forEach(
                (symbol, list) -> deltas.add(new BookDelta("book", symbol, ++state(symbol).seq, List.copyOf(list))));
        if (subscribers.isEmpty()) {
            return;
        }
        // Trades were numbered before the book change they caused, so they go out first.
        for (TradeMessage trade : trades) {
            publish(trade.symbol(), trade);
        }
        for (BookDelta delta : deltas) {
            publish(delta.symbol(), delta);
        }
        if (sessionChanged) {
            publishAll(new SessionMessage("session", session, simTime));
        }
    }

    /**
     * Applies a replayed event during restart: state only, nothing is published (there are no subscribers yet).
     * Every replayed command's events arrive here in order, so the derived book ends exactly where trading stopped.
     */
    public synchronized void replay(ExchangeEvent event) {
        tracker.onEvent(event);
        simTime = Math.max(simTime, event.simTime());
        if (event instanceof Trade t) {
            recordTrade(t);
        } else if (event instanceof SessionStateChanged s) {
            session = s.state();
            if (s.state() == SessionState.OPEN) {
                symbols.values().forEach(MarketHub::resetSessionStats);
            }
        }
    }

    // ---- snapshots (ADR 0016)
    // -----------------------------------------------------------------------------------------

    /** Per-symbol statistics and recent trades, as a snapshot stores them. */
    public record SymbolSnapshot(
            String symbol,
            long seq,
            long last,
            long open,
            long high,
            long low,
            long volume,
            long tradeCount,
            List<TradeMessage> trades) {}

    /** What a snapshot needs from market data besides the order tracker. */
    public record MarketSnapshot(SessionState session, long simTime, List<SymbolSnapshot> symbols) {}

    /** The derived open orders and book, for a snapshot. Called by the outbound stage, between commands. */
    public synchronized OrderTracker.State trackerState() {
        return tracker.state();
    }

    public synchronized MarketSnapshot marketState() {
        List<SymbolSnapshot> out = new ArrayList<>();
        for (SymbolState st : symbols.values()) {
            out.add(new SymbolSnapshot(
                    st.symbol,
                    st.seq,
                    st.last,
                    st.open,
                    st.high,
                    st.low,
                    st.volume,
                    st.tradeCount,
                    List.copyOf(st.trades)));
        }
        return new MarketSnapshot(session, simTime, out);
    }

    /** Starts from a snapshot, before the events replayed after it are applied with {@link #replay}. */
    public synchronized void restore(OrderTracker.State trackerState, MarketSnapshot market) {
        tracker.restore(trackerState);
        session = market.session();
        simTime = market.simTime();
        for (SymbolSnapshot snap : market.symbols()) {
            SymbolState st = state(snap.symbol());
            st.seq = snap.seq();
            st.last = snap.last();
            st.open = snap.open();
            st.high = snap.high();
            st.low = snap.low();
            st.volume = snap.volume();
            st.tradeCount = snap.tradeCount();
            st.trades.clear();
            st.trades.addAll(snap.trades());
        }
    }

    /** Ends a replay: settles the derived book (its intermediate changes were never published). */
    public synchronized void finishReplay() {
        tracker.endCommand();
    }

    /**
     * Subscribes to the given symbols: one snapshot per symbol, then every later message for them (and session
     * changes). A subscriber that falls more than {@code subscriberBuffer} messages behind is disconnected rather than
     * slowing everyone down; it can reconnect and get a fresh snapshot.
     */
    public synchronized Flux<MarketMessage> subscribe(Set<String> wanted, int depth) {
        List<MarketMessage> snapshots = new ArrayList<>();
        for (String symbol : wanted) {
            if (symbols.containsKey(symbol)) {
                snapshots.add(snapshot(symbol, depth));
            }
        }
        Sinks.Many<MarketMessage> sink = Sinks.many()
                .unicast()
                .onBackpressureBuffer(
                        Queues.<MarketMessage>get(subscriberBuffer).get());
        Subscriber subscriber = new Subscriber(Set.copyOf(wanted), sink);
        subscribers.add(subscriber);
        return Flux.concat(Flux.fromIterable(snapshots), sink.asFlux())
                .doFinally(signal -> subscribers.remove(subscriber));
    }

    /** The book and recent trades of one symbol, or null if it is not listed. */
    public synchronized Snapshot snapshot(String symbol, int depth) {
        SymbolState state = symbols.get(symbol);
        if (state == null) {
            return null;
        }
        List<TradeMessage> recent = new ArrayList<>(state.trades);
        int from = Math.max(0, recent.size() - 100);
        return new Snapshot(
                "snapshot",
                symbol,
                state.seq,
                session,
                levels(symbol, Side.BUY, depth),
                levels(symbol, Side.SELL, depth),
                List.copyOf(recent.subList(from, recent.size())),
                ticker(state));
    }

    /** Up to {@code limit} most recent trades, oldest first. */
    public synchronized List<TradeMessage> trades(String symbol, int limit) {
        SymbolState state = symbols.get(symbol);
        if (state == null) {
            return List.of();
        }
        List<TradeMessage> all = new ArrayList<>(state.trades);
        return List.copyOf(all.subList(Math.max(0, all.size() - limit), all.size()));
    }

    public synchronized List<Ticker> tickers() {
        return symbols.values().stream().map(this::ticker).toList();
    }

    public synchronized Ticker ticker(String symbol) {
        SymbolState state = symbols.get(symbol);
        return state == null ? null : ticker(state);
    }

    public synchronized List<OrderTracker.OpenOrder> openOrders(long accountId) {
        return tracker.openOrders(accountId);
    }

    public synchronized OrderTracker.OpenOrder openOrder(long orderId) {
        return tracker.openOrder(orderId);
    }

    public synchronized SessionState session() {
        return session;
    }

    public synchronized long simTime() {
        return simTime;
    }

    public synchronized Set<String> listedSymbols() {
        return Set.copyOf(symbols.keySet());
    }

    public int subscriberCount() {
        return subscribers.size();
    }

    private TradeMessage recordTrade(Trade t) {
        SymbolState state = state(t.symbol());
        TradeMessage message = new TradeMessage(
                "trade", t.symbol(), ++state.seq, t.tradeId(), t.price(), t.quantity(), t.aggressorSide(), t.simTime());
        state.trades.addLast(message);
        if (state.trades.size() > tradeHistory) {
            state.trades.removeFirst();
        }
        if (state.tradeCount == 0) {
            state.open = t.price();
            state.high = t.price();
            state.low = t.price();
        }
        state.last = t.price();
        state.high = Math.max(state.high, t.price());
        state.low = Math.min(state.low, t.price());
        state.volume += t.quantity();
        state.tradeCount++;
        return message;
    }

    private static void resetSessionStats(SymbolState state) {
        state.open = 0;
        state.high = 0;
        state.low = 0;
        state.volume = 0;
        state.tradeCount = 0;
    }

    private Ticker ticker(SymbolState state) {
        List<DepthLevel> bid = tracker.depth(state.symbol, Side.BUY, 1);
        List<DepthLevel> ask = tracker.depth(state.symbol, Side.SELL, 1);
        return new Ticker(
                state.symbol,
                state.referencePrice,
                state.last,
                state.open,
                state.high,
                state.low,
                state.volume,
                state.tradeCount,
                bid.isEmpty() ? 0 : bid.getFirst().price(),
                ask.isEmpty() ? 0 : ask.getFirst().price());
    }

    private List<Level> levels(String symbol, Side side, int depth) {
        return tracker.depth(symbol, side, depth).stream()
                .map(l -> new Level(l.price(), l.quantity(), l.orderCount()))
                .toList();
    }

    private SymbolState state(String symbol) {
        return symbols.computeIfAbsent(symbol, SymbolState::new);
    }

    private void publish(String symbol, MarketMessage message) {
        for (Subscriber subscriber : subscribers) {
            if (subscriber.symbols().contains(symbol)) {
                emit(subscriber, message);
            }
        }
    }

    private void publishAll(MarketMessage message) {
        for (Subscriber subscriber : subscribers) {
            emit(subscriber, message);
        }
    }

    private void emit(Subscriber subscriber, MarketMessage message) {
        Sinks.EmitResult result = subscriber.sink().tryEmitNext(message);
        if (result.isFailure()) {
            subscribers.remove(subscriber);
            subscriber.sink().tryEmitError(new IllegalStateException("market-data subscriber too slow: " + result));
            log.warn("dropped a market-data subscriber: {}", result);
        }
    }
}
