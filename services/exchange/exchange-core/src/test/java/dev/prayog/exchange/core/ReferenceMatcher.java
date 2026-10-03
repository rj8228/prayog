package dev.prayog.exchange.core;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A deliberately naive matcher used as a test oracle. It keeps every resting order in one list and scans it in full
 * for each step: too slow for real use, but simple enough to check by eye. The real engine must produce exactly the
 * same events.
 *
 * <p>Time priority is an explicit arrival counter: an order that loses priority simply gets a new arrival number.
 */
final class ReferenceMatcher {

    private static final class Resting {
        final long orderId;
        final long accountId;
        final Side side;
        long price;
        long arrival;
        long quantity;
        long leaves;

        Resting(long orderId, long accountId, Side side, long price, long arrival, long quantity, long leaves) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.quantity = quantity;
            this.leaves = leaves;
        }
    }

    private final Map<String, Instrument> instruments;
    private final List<Resting> resting = new ArrayList<>();
    final List<ExchangeEvent> events = new ArrayList<>();
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
            default -> throw new IllegalArgumentException("not modelled yet: " + command);
        }
    }

    private void newOrder(NewOrder o) {
        Instrument instrument = instruments.get(o.symbol());
        RejectReason reason = null;
        if (instrument == null) {
            reason = RejectReason.UNKNOWN_SYMBOL;
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
        long leaves = trade(id, o.accountId(), o.side(), market, o.price(), o.quantity(), o.symbol());
        if (leaves > 0 && market) {
            events.add(
                    new OrderCancelled(seq++, time, id, o.accountId(), o.symbol(), leaves, CancelReason.NO_LIQUIDITY));
        } else if (leaves > 0) {
            resting.add(new Resting(id, o.accountId(), o.side(), o.price(), arrival++, o.quantity(), leaves));
        }
    }

    private void cancel(CancelOrder c) {
        Resting r = find(c.symbol(), c.orderId(), c.accountId());
        if (r == null) {
            rejectExisting(c.orderId(), c.clientOrderId(), c.accountId(), c.symbol());
            return;
        }
        resting.remove(r);
        events.add(new OrderCancelled(
                seq++, time, r.orderId, r.accountId, c.symbol(), r.leaves, CancelReason.CLIENT_REQUEST));
    }

    private void modify(ModifyOrder m) {
        Resting r = find(m.symbol(), m.orderId(), m.accountId());
        if (r == null) {
            rejectExisting(m.orderId(), m.clientOrderId(), m.accountId(), m.symbol());
            return;
        }
        Instrument instrument = instruments.get(m.symbol());
        RejectReason reason = m.quantity() < 1 || m.quantity() > instrument.maxOrderQuantity()
                ? RejectReason.INVALID_QUANTITY
                : priceProblem(m.price(), instrument);
        if (reason != null) {
            events.add(
                    new OrderRejected(seq++, time, m.orderId(), m.clientOrderId(), m.accountId(), m.symbol(), reason));
            return;
        }
        long filled = r.quantity - r.leaves;
        if (m.quantity() <= filled) {
            resting.remove(r);
            events.add(new OrderCancelled(
                    seq++, time, r.orderId, r.accountId, m.symbol(), r.leaves, CancelReason.MODIFIED_TO_ZERO));
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
        long leaves = trade(r.orderId, r.accountId, r.side, false, m.price(), newLeaves, m.symbol());
        if (leaves > 0) {
            resting.add(new Resting(r.orderId, r.accountId, r.side, m.price(), arrival++, m.quantity(), leaves));
        }
    }

    /** Matches an incoming quantity against resting orders; returns what is left. */
    private long trade(long id, long account, Side side, boolean market, long limit, long quantity, String symbol) {
        long leaves = quantity;
        while (leaves > 0) {
            Resting best = bestCounterparty(side, market, limit);
            if (best == null) {
                break;
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
        return leaves;
    }

    /** Best opposite order the incoming one can trade with: best price, then earliest arrival. */
    private Resting bestCounterparty(Side side, boolean market, long limit) {
        Comparator<Resting> byPrice = Comparator.comparingLong(r -> r.price);
        if (side == Side.SELL) {
            byPrice = byPrice.reversed(); // a seller wants the highest bid
        }
        return resting.stream()
                .filter(r -> r.side != side)
                .filter(r -> market || (side == Side.BUY ? r.price <= limit : r.price >= limit))
                .min(byPrice.thenComparingLong(r -> r.arrival))
                .orElse(null);
    }

    private Resting find(String symbol, long id, long account) {
        if (!instruments.containsKey(symbol)) {
            return null;
        }
        return resting.stream()
                .filter(r -> r.orderId == id && r.accountId == account)
                .findFirst()
                .orElse(null);
    }

    private void rejectExisting(long id, String clientOrderId, long account, String symbol) {
        RejectReason reason =
                instruments.containsKey(symbol) ? RejectReason.UNKNOWN_ORDER : RejectReason.UNKNOWN_SYMBOL;
        events.add(new OrderRejected(seq++, time, id, clientOrderId, account, symbol, reason));
    }

    private static RejectReason priceProblem(long price, Instrument instrument) {
        if (price < 1) {
            return RejectReason.INVALID_PRICE;
        }
        return price % instrument.tickSize() != 0 ? RejectReason.PRICE_NOT_ON_TICK : null;
    }
}
