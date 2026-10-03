package dev.prayog.exchange.core;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A deliberately naive matcher used as a test oracle. It keeps every resting order in one list and scans it in full
 * for each step: too slow for real use, but simple enough to check by eye. The real engine must produce exactly the
 * same events.
 *
 * <p>Time priority is an explicit arrival counter: an order that loses priority simply gets a new arrival number.
 */
final class ReferenceMatcher {

    private static final class Resting {
        final String symbol;
        final long orderId;
        final long accountId;
        final Side side;
        long price;
        long arrival;
        long quantity;
        long leaves;

        Resting(
                String symbol,
                long orderId,
                long accountId,
                Side side,
                long price,
                long arrival,
                long quantity,
                long leaves) {
            this.symbol = symbol;
            this.orderId = orderId;
            this.accountId = accountId;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.quantity = quantity;
            this.leaves = leaves;
        }
    }

    /** What an incoming quantity did: how much is left, and whether a self-trade stopped it. */
    private record Outcome(long leaves, boolean selfTrade) {}

    private final Map<String, Instrument> instruments;
    private final List<Resting> resting = new ArrayList<>();
    private final Set<Long> disabled = new TreeSet<>();
    final List<ExchangeEvent> events = new ArrayList<>();
    private SessionState session = SessionState.CLOSED;
    private long time;
    private long seq = 1;
    private long orderId = 1;
    private long tradeId = 1;
    private long arrival = 0;

    ReferenceMatcher(Map<String, Instrument> instruments) {
        this.instruments = instruments;
    }

    void apply(Command command) {
        switch (command) {
            case ClockTick t -> time = Math.max(time, t.simTime());
            case NewOrder o -> newOrder(o);
            case CancelOrder c -> cancel(c);
            case ModifyOrder m -> modify(m);
            case SetSessionState s -> session(s.state());
            case SetAccountEnabled a -> account(a.accountId(), a.enabled());
        }
    }

    private void newOrder(NewOrder o) {
        Instrument instrument = instruments.get(o.symbol());
        RejectReason reason;
        if (instrument == null) {
            reason = RejectReason.UNKNOWN_SYMBOL;
        } else if (disabled.contains(o.accountId())) {
            reason = RejectReason.ACCOUNT_DISABLED;
        } else if (session != SessionState.OPEN) {
            reason = RejectReason.SESSION_NOT_OPEN;
        } else if (o.quantity() < 1 || o.quantity() > instrument.maxOrderQuantity()) {
            reason = RejectReason.INVALID_QUANTITY;
        } else if (o.type() == OrderType.MARKET) {
            reason = o.price() != 0 ? RejectReason.INVALID_PRICE : null;
        } else {
            reason = priceProblem(o.price(), instrument);
        }
        if (reason != null) {
            events.add(new OrderRejected(seq++, time, 0, o.clientOrderId(), o.accountId(), o.symbol(), reason));
            return;
        }
        long id = orderId++;
        events.add(new OrderAccepted(
                seq++,
                time,
                id,
                o.clientOrderId(),
                o.accountId(),
                o.symbol(),
                o.side(),
                o.type(),
                o.price(),
                o.quantity()));
        boolean market = o.type() == OrderType.MARKET;
        long limit = !market ? o.price() : o.side() == Side.BUY ? instrument.bandHigh() : instrument.bandLow();
        Outcome out = trade(id, o.accountId(), o.side(), limit, o.quantity(), o.symbol());
        if (out.leaves() == 0) {
            return;
        }
        if (out.selfTrade() || market) {
            CancelReason why = out.selfTrade() ? CancelReason.SELF_TRADE_PREVENTION : CancelReason.NO_LIQUIDITY;
            events.add(new OrderCancelled(seq++, time, id, o.accountId(), o.symbol(), out.leaves(), why));
        } else {
            resting.add(new Resting(
                    o.symbol(), id, o.accountId(), o.side(), o.price(), arrival++, o.quantity(), out.leaves()));
        }
    }

    private void cancel(CancelOrder c) {
        if (instruments.containsKey(c.symbol()) && session == SessionState.CLOSED) {
            reject(c.orderId(), c.clientOrderId(), c.accountId(), c.symbol(), RejectReason.SESSION_NOT_OPEN);
            return;
        }
        Resting r = find(c.symbol(), c.orderId(), c.accountId());
        if (r == null) {
            rejectMissing(c.orderId(), c.clientOrderId(), c.accountId(), c.symbol());
            return;
        }
        drop(r, CancelReason.CLIENT_REQUEST);
    }

    private void modify(ModifyOrder m) {
        if (instruments.containsKey(m.symbol()) && session != SessionState.OPEN) {
            reject(m.orderId(), m.clientOrderId(), m.accountId(), m.symbol(), RejectReason.SESSION_NOT_OPEN);
            return;
        }
        Resting r = find(m.symbol(), m.orderId(), m.accountId());
        if (r == null) {
            rejectMissing(m.orderId(), m.clientOrderId(), m.accountId(), m.symbol());
            return;
        }
        Instrument instrument = instruments.get(m.symbol());
        RejectReason reason = m.quantity() < 1 || m.quantity() > instrument.maxOrderQuantity()
                ? RejectReason.INVALID_QUANTITY
                : priceProblem(m.price(), instrument);
        if (reason != null) {
            reject(m.orderId(), m.clientOrderId(), m.accountId(), m.symbol(), reason);
            return;
        }
        long filled = r.quantity - r.leaves;
        if (m.quantity() <= filled) {
            drop(r, CancelReason.MODIFIED_TO_ZERO);
            return;
        }
        long newLeaves = m.quantity() - filled;
        boolean keepsPriority = m.price() == r.price && m.quantity() <= r.quantity;
        events.add(
                new OrderModified(seq++, time, r.orderId, r.accountId, m.symbol(), m.price(), m.quantity(), newLeaves));
        if (keepsPriority) {
            r.quantity = m.quantity();
            r.leaves = newLeaves;
            return;
        }
        resting.remove(r);
        Outcome out = trade(r.orderId, r.accountId, r.side, m.price(), newLeaves, m.symbol());
        if (out.leaves() > 0 && out.selfTrade()) {
            events.add(new OrderCancelled(
                    seq++, time, r.orderId, r.accountId, m.symbol(), out.leaves(), CancelReason.SELF_TRADE_PREVENTION));
        } else if (out.leaves() > 0) {
            resting.add(new Resting(
                    m.symbol(), r.orderId, r.accountId, r.side, m.price(), arrival++, m.quantity(), out.leaves()));
        }
    }

    private void session(SessionState next) {
        if (next == session) {
            return;
        }
        session = next;
        events.add(new SessionStateChanged(seq++, time, next));
        if (next == SessionState.CLOSED) {
            sortedBySymbolThenId().forEach(r -> drop(r, CancelReason.EXPIRED));
        }
    }

    private void account(long account, boolean enabled) {
        if (enabled) {
            disabled.remove(account);
            return;
        }
        disabled.add(account);
        sortedBySymbolThenId().stream()
                .filter(r -> r.accountId == account)
                .forEach(r -> drop(r, CancelReason.KILL_SWITCH));
    }

    /** Matches an incoming quantity; stops before trading with the same account (cancel incoming). */
    private Outcome trade(long id, long account, Side side, long limit, long quantity, String symbol) {
        long leaves = quantity;
        while (leaves > 0) {
            Resting best = bestCounterparty(symbol, side, limit);
            if (best == null) {
                break;
            }
            if (best.accountId == account) {
                return new Outcome(leaves, true);
            }
            long qty = Math.min(leaves, best.leaves);
            boolean buy = side == Side.BUY;
            events.add(new Trade(
                    seq++,
                    time,
                    tradeId++,
                    symbol,
                    best.price,
                    qty,
                    side,
                    buy ? id : best.orderId,
                    buy ? best.orderId : id,
                    buy ? account : best.accountId,
                    buy ? best.accountId : account));
            leaves -= qty;
            best.leaves -= qty;
            if (best.leaves == 0) {
                resting.remove(best);
            }
        }
        return new Outcome(leaves, false);
    }

    /** Best opposite order the incoming one can trade with: best price, then earliest arrival. */
    private Resting bestCounterparty(String symbol, Side side, long limit) {
        Comparator<Resting> byPrice = Comparator.comparingLong(r -> r.price);
        if (side == Side.SELL) {
            byPrice = byPrice.reversed(); // a seller wants the highest bid
        }
        return resting.stream()
                .filter(r -> r.symbol.equals(symbol) && r.side != side)
                .filter(r -> side == Side.BUY ? r.price <= limit : r.price >= limit)
                .min(byPrice.thenComparingLong(r -> r.arrival))
                .orElse(null);
    }

    private List<Resting> sortedBySymbolThenId() {
        return resting.stream()
                .sorted(Comparator.comparing((Resting r) -> r.symbol).thenComparingLong(r -> r.orderId))
                .toList();
    }

    private Resting find(String symbol, long id, long account) {
        return resting.stream()
                .filter(r -> r.symbol.equals(symbol) && r.orderId == id && r.accountId == account)
                .findFirst()
                .orElse(null);
    }

    private void drop(Resting r, CancelReason reason) {
        resting.remove(r);
        events.add(new OrderCancelled(seq++, time, r.orderId, r.accountId, r.symbol, r.leaves, reason));
    }

    private void reject(long id, String clientOrderId, long account, String symbol, RejectReason reason) {
        events.add(new OrderRejected(seq++, time, id, clientOrderId, account, symbol, reason));
    }

    private void rejectMissing(long id, String clientOrderId, long account, String symbol) {
        reject(
                id,
                clientOrderId,
                account,
                symbol,
                instruments.containsKey(symbol) ? RejectReason.UNKNOWN_ORDER : RejectReason.UNKNOWN_SYMBOL);
    }

    private static RejectReason priceProblem(long price, Instrument instrument) {
        if (price < 1) {
            return RejectReason.INVALID_PRICE;
        }
        if (price % instrument.tickSize() != 0) {
            return RejectReason.PRICE_NOT_ON_TICK;
        }
        return price < instrument.bandLow() || price > instrument.bandHigh() ? RejectReason.PRICE_OUTSIDE_BAND : null;
    }
}
