package dev.prayog.exchange.core;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Applies exchange rules to incoming commands and emits events. Price-time priority: the best price trades first, and
 * at one price the oldest order trades first. A trade always happens at the resting order's price.
 *
 * <p>Single-threaded and deterministic: it owns every counter, takes time only from {@link ClockTick} commands, and
 * never iterates a hash map, so the same commands always give the same events. Bulk operations walk the books in
 * symbol order (a {@link TreeMap}) and each book in order-ID order.
 *
 * <p>Checks run in a fixed order, so a reject always has one predictable reason: symbol, account enabled, session,
 * quantity, price, tick, band.
 */
public final class MatchingEngine {

    /**
     * Matching-rules versions (ADR 0014). A new engine starts at 1 so old journals replay unchanged; the exchange
     * journals {@link SetRules} to move to the latest.
     *
     * <ul>
     *   <li>1: S4-S13 rules.
     *   <li>2: duplicate client order IDs are rejected per account per trading day.
     * </ul>
     */
    public static final int LATEST_RULES = 2;

    private final Map<String, Instrument> instruments = new HashMap<>();
    private final NavigableMap<String, OrderBook> books = new TreeMap<>();
    private final Set<Long> disabledAccounts = new HashSet<>(); // lookups only, never iterated
    private final ClientOrderIds clientOrderIds = new ClientOrderIds();
    private final SessionSchedule schedule; // null: sessions change only by ops command
    private final EventSink sink;

    private SessionState session = SessionState.CLOSED;
    private long simTime;
    private boolean ticked;
    private long nextEventSeq = 1;
    private long nextOrderId = 1;
    private long nextTradeId = 1;
    private int rulesVersion = 1;

    /** Set by {@link #match} when it stopped because the next fill would have been with the same account. */
    private boolean stoppedBySelfTrade;

    /** An engine whose sessions change only through {@link SetSessionState} commands. */
    public MatchingEngine(Collection<Instrument> instruments, EventSink sink) {
        this(instruments, null, sink);
    }

    /** An engine that also opens and closes the market itself when clock ticks cross the schedule's times. */
    public MatchingEngine(Collection<Instrument> instruments, SessionSchedule schedule, EventSink sink) {
        this.schedule = schedule;
        this.sink = Objects.requireNonNull(sink, "sink");
        for (Instrument instrument : instruments) {
            this.instruments.put(instrument.symbol(), instrument);
            this.books.put(instrument.symbol(), new OrderBook(instrument.symbol()));
        }
    }

    /** The book for a symbol, or null if the symbol is not listed. */
    public OrderBook book(String symbol) {
        return books.get(symbol);
    }

    /** Applies one command. Every outcome, including a refusal, is reported as events. */
    public void apply(Command command) {
        switch (command) {
            case NewOrder order -> newOrder(order);
            case CancelOrder cancel -> cancel(cancel);
            case ModifyOrder modify -> modify(modify);
            case ClockTick tick -> tick(tick.simTime());
            case SetSessionState state -> setSession(state.state());
            case SetAccountEnabled account -> setAccountEnabled(account.accountId(), account.enabled());
            case SetRules rules -> setRules(rules.version());
            case TakeSnapshot snapshot -> {
                // nothing changes; the pipeline copies the state (see ExchangePipeline)
            }
        }
    }

    /** A copy of the engine's whole state (ADR 0016). Call on the matching thread; cost grows with resting orders. */
    public EngineState snapshot() {
        List<EngineState.Order> orders = new ArrayList<>();
        for (OrderBook book : books.values()) {
            for (RestingOrder o : book.ordersInPriorityOrder()) {
                orders.add(new EngineState.Order(
                        book.symbol(), o.orderId, o.accountId, o.side, o.price, o.quantity, o.leavesQuantity));
            }
        }
        return new EngineState(
                rulesVersion,
                session,
                simTime,
                ticked,
                nextEventSeq,
                nextOrderId,
                nextTradeId,
                disabledAccounts.stream().sorted().toList(),
                clientOrderIds.export(),
                orders);
    }

    /** An engine in exactly the state {@code state} describes, emitting into {@code sink} from now on. */
    public static MatchingEngine restore(
            Collection<Instrument> instruments, SessionSchedule schedule, EngineState state, EventSink sink) {
        MatchingEngine engine = new MatchingEngine(instruments, schedule, sink);
        engine.rulesVersion = state.rulesVersion();
        engine.session = state.session();
        engine.simTime = state.simTime();
        engine.ticked = state.ticked();
        engine.nextEventSeq = state.nextEventSeq();
        engine.nextOrderId = state.nextOrderId();
        engine.nextTradeId = state.nextTradeId();
        engine.disabledAccounts.addAll(state.disabledAccounts());
        engine.clientOrderIds.restore(state.clientOrderIds());
        for (EngineState.Order o : state.orders()) {
            OrderBook book = engine.books.get(o.symbol());
            if (book == null) {
                throw new IllegalArgumentException("snapshot has an order for unlisted symbol " + o.symbol());
            }
            // Added in queue order, so each order gets back its place in the queue.
            book.add(new RestingOrder(
                    o.orderId(), o.accountId(), o.side(), o.price(), o.quantity(), o.leavesQuantity()));
        }
        return engine;
    }

    /** The matching-rules version in force. */
    public int rulesVersion() {
        return rulesVersion;
    }

    // Moves forward only: going back would change the meaning of orders already accepted. Emits no event.
    private void setRules(int version) {
        if (version > rulesVersion && version <= LATEST_RULES) {
            rulesVersion = version;
        }
    }

    private void newOrder(NewOrder order) {
        Instrument instrument = instruments.get(order.symbol());
        RejectReason reason = instrument == null ? RejectReason.UNKNOWN_SYMBOL : validateNew(order, instrument);
        if (reason != null) {
            reject(0, order.clientOrderId(), order.accountId(), order.symbol(), reason);
            return;
        }

        clientOrderIds.add(order.accountId(), order.clientOrderId());
        long orderId = nextOrderId++;
        sink.accept(new OrderAccepted(
                nextEventSeq++,
                simTime,
                orderId,
                order.clientOrderId(),
                order.accountId(),
                order.symbol(),
                order.side(),
                order.type(),
                order.price(),
                order.quantity()));

        OrderBook book = books.get(order.symbol());
        boolean market = order.type() == OrderType.MARKET;
        long limit = market ? marketLimit(order.side(), instrument) : order.price();
        long leaves = match(book, orderId, order.accountId(), order.side(), limit, order.quantity());
        if (leaves == 0) {
            return;
        }
        if (stoppedBySelfTrade) {
            cancelled(orderId, order.accountId(), book.symbol(), leaves, CancelReason.SELF_TRADE_PREVENTION);
        } else if (market) {
            // A market order never rests: whatever the book could not fill is cancelled.
            cancelled(orderId, order.accountId(), book.symbol(), leaves, CancelReason.NO_LIQUIDITY);
        } else {
            book.add(new RestingOrder(
                    orderId, order.accountId(), order.side(), order.price(), order.quantity(), leaves));
        }
    }

    private void cancel(CancelOrder cancel) {
        OrderBook book = books.get(cancel.symbol());
        if (book != null && session == SessionState.CLOSED) {
            // Cancels are allowed while HALTED so people can reduce risk; CLOSED has no live orders anyway.
            reject(
                    cancel.orderId(),
                    cancel.clientOrderId(),
                    cancel.accountId(),
                    cancel.symbol(),
                    RejectReason.SESSION_NOT_OPEN);
            return;
        }
        RestingOrder order = book == null ? null : ownOrder(book, cancel.orderId(), cancel.accountId());
        if (order == null) {
            reject(cancel.orderId(), cancel.clientOrderId(), cancel.accountId(), cancel.symbol(), missing(book));
            return;
        }
        removeAndCancel(book, order, CancelReason.CLIENT_REQUEST);
    }

    /**
     * Priority rules: reducing quantity at the same price keeps the order's place in the queue (it harms nobody
     * behind it). Raising quantity or changing price is treated like a new order: back of the queue, and it may trade
     * at once if the new price crosses.
     */
    private void modify(ModifyOrder modify) {
        OrderBook book = books.get(modify.symbol());
        if (book != null && session != SessionState.OPEN) {
            reject(
                    modify.orderId(),
                    modify.clientOrderId(),
                    modify.accountId(),
                    modify.symbol(),
                    RejectReason.SESSION_NOT_OPEN);
            return;
        }
        RestingOrder order = book == null ? null : ownOrder(book, modify.orderId(), modify.accountId());
        if (order == null) {
            reject(modify.orderId(), modify.clientOrderId(), modify.accountId(), modify.symbol(), missing(book));
            return;
        }
        RejectReason reason = validateModify(modify, instruments.get(modify.symbol()));
        if (reason != null) {
            reject(modify.orderId(), modify.clientOrderId(), modify.accountId(), modify.symbol(), reason);
            return;
        }

        long filled = order.quantity - order.leavesQuantity;
        if (modify.quantity() <= filled) {
            // Nothing would be left open (BUILD_PLAN 16.3): cancel the open part.
            removeAndCancel(book, order, CancelReason.MODIFIED_TO_ZERO);
            return;
        }

        long newLeaves = modify.quantity() - filled;
        if (modify.price() == order.price && modify.quantity() <= order.quantity) {
            book.reduce(order, order.leavesQuantity - newLeaves);
            order.quantity = modify.quantity();
            modified(order, modify, newLeaves);
            return;
        }

        book.remove(order);
        modified(order, modify, newLeaves);
        long leaves = match(book, order.orderId, order.accountId, order.side, modify.price(), newLeaves);
        if (leaves > 0 && stoppedBySelfTrade) {
            cancelled(order.orderId, order.accountId, book.symbol(), leaves, CancelReason.SELF_TRADE_PREVENTION);
        } else if (leaves > 0) {
            book.add(new RestingOrder(
                    order.orderId, order.accountId, order.side, modify.price(), modify.quantity(), leaves));
        }
    }

    /**
     * Trades an incoming quantity against the opposite side while prices cross. Returns the unfilled quantity. The
     * incoming order is not on the book while it matches.
     *
     * <p>Self-trade prevention (cancel incoming): if the next resting order to fill belongs to the same account,
     * matching stops and {@link #stoppedBySelfTrade} is set; the caller cancels the remainder. Fills already made with
     * other accounts stand.
     */
    private long match(OrderBook book, long orderId, long accountId, Side side, long limit, long quantity) {
        stoppedBySelfTrade = false;
        long leaves = quantity;
        PriceLevel level;
        while (leaves > 0 && (level = book.bestLevel(side.opposite())) != null && crosses(side, limit, level)) {
            RestingOrder resting = level.head();
            if (resting.accountId == accountId) {
                stoppedBySelfTrade = true;
                break;
            }
            long fill = Math.min(leaves, resting.leavesQuantity);
            boolean buying = side == Side.BUY;
            sink.accept(new Trade(
                    nextEventSeq++,
                    simTime,
                    nextTradeId++,
                    book.symbol(),
                    resting.price,
                    fill,
                    side,
                    buying ? orderId : resting.orderId,
                    buying ? resting.orderId : orderId,
                    buying ? accountId : resting.accountId,
                    buying ? resting.accountId : accountId));
            leaves -= fill;
            book.fill(resting, fill);
        }
        return leaves;
    }

    /** A buy crosses an ask at or below its limit; a sell crosses a bid at or above its limit. */
    private static boolean crosses(Side side, long limit, PriceLevel opposite) {
        return side == Side.BUY ? opposite.price <= limit : opposite.price >= limit;
    }

    /** The worst price a market order accepts: the band edge, so it can never trade at an absurd price. */
    private static long marketLimit(Side side, Instrument instrument) {
        return side == Side.BUY ? instrument.bandHigh() : instrument.bandLow();
    }

    /**
     * Advances sim time (never backwards) and applies the schedule. When a tick lands in a different scheduled period
     * than the previous one, a boundary was crossed and the session is set to what the schedule says. Between
     * boundaries an ops override (an early open, a halt) stands.
     */
    private void tick(long time) {
        long previous = simTime;
        boolean first = !ticked;
        simTime = Math.max(simTime, time);
        ticked = true;
        if (schedule == null || (!first && schedule.period(previous) == schedule.period(simTime))) {
            return;
        }
        if (!schedule.isOpenAt(simTime)) {
            setSession(SessionState.CLOSED);
            return;
        }
        if (!first && schedule.day(previous) != schedule.day(simTime)) {
            setSession(SessionState.CLOSED); // the tick skipped a close: expire yesterday's orders first
        }
        setSession(SessionState.OPEN);
    }

    private void setSession(SessionState next) {
        if (next == session) {
            return;
        }
        session = next;
        sink.accept(new SessionStateChanged(nextEventSeq++, simTime, next));
        if (next == SessionState.CLOSED) {
            clientOrderIds.clear(); // a new day: client order IDs may be reused
            // Every order is a DAY order: whatever is still open expires at the close.
            for (OrderBook book : books.values()) {
                for (RestingOrder order : book.ordersInIdOrder()) {
                    removeAndCancel(book, order, CancelReason.EXPIRED);
                }
            }
        }
    }

    /** Disabling is the per-account kill switch: cancel everything the account has open and refuse new orders. */
    private void setAccountEnabled(long accountId, boolean enabled) {
        if (enabled) {
            disabledAccounts.remove(accountId);
            return;
        }
        disabledAccounts.add(accountId);
        for (OrderBook book : books.values()) {
            for (RestingOrder order : book.ordersInIdOrder()) {
                if (order.accountId == accountId) {
                    removeAndCancel(book, order, CancelReason.KILL_SWITCH);
                }
            }
        }
    }

    private void removeAndCancel(OrderBook book, RestingOrder order, CancelReason reason) {
        long open = order.leavesQuantity;
        book.remove(order);
        cancelled(order.orderId, order.accountId, book.symbol(), open, reason);
    }

    /** Another account's order is reported exactly like a missing one, so its existence is not revealed. */
    private static RestingOrder ownOrder(OrderBook book, long orderId, long accountId) {
        RestingOrder order = book.order(orderId);
        return order != null && order.accountId == accountId ? order : null;
    }

    private static RejectReason missing(OrderBook book) {
        return book == null ? RejectReason.UNKNOWN_SYMBOL : RejectReason.UNKNOWN_ORDER;
    }

    /** First failing check wins, so a reject always has one clear reason. */
    private RejectReason validateNew(NewOrder order, Instrument instrument) {
        if (disabledAccounts.contains(order.accountId())) {
            return RejectReason.ACCOUNT_DISABLED;
        }
        if (session != SessionState.OPEN) {
            return RejectReason.SESSION_NOT_OPEN;
        }
        if (rulesVersion >= 2 && clientOrderIds.contains(order.accountId(), order.clientOrderId())) {
            return RejectReason.DUPLICATE_CLIENT_ORDER_ID;
        }
        if (order.quantity() <= 0 || order.quantity() > instrument.maxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        if (order.type() == OrderType.MARKET) {
            return order.price() == 0 ? null : RejectReason.INVALID_PRICE;
        }
        return validatePrice(order.price(), instrument);
    }

    private static RejectReason validateModify(ModifyOrder modify, Instrument instrument) {
        if (modify.quantity() <= 0 || modify.quantity() > instrument.maxOrderQuantity()) {
            return RejectReason.INVALID_QUANTITY;
        }
        return validatePrice(modify.price(), instrument);
    }

    private static RejectReason validatePrice(long price, Instrument instrument) {
        if (price <= 0) {
            return RejectReason.INVALID_PRICE;
        }
        if (price % instrument.tickSize() != 0) {
            return RejectReason.PRICE_NOT_ON_TICK;
        }
        if (price < instrument.bandLow() || price > instrument.bandHigh()) {
            return RejectReason.PRICE_OUTSIDE_BAND;
        }
        return null;
    }

    private void reject(long orderId, String clientOrderId, long accountId, String symbol, RejectReason reason) {
        sink.accept(new OrderRejected(nextEventSeq++, simTime, orderId, clientOrderId, accountId, symbol, reason));
    }

    private void cancelled(long orderId, long accountId, String symbol, long quantity, CancelReason reason) {
        sink.accept(new OrderCancelled(nextEventSeq++, simTime, orderId, accountId, symbol, quantity, reason));
    }

    private void modified(RestingOrder order, ModifyOrder modify, long leaves) {
        sink.accept(new OrderModified(
                nextEventSeq++,
                simTime,
                order.orderId,
                order.accountId,
                modify.symbol(),
                modify.price(),
                modify.quantity(),
                leaves));
    }
}
