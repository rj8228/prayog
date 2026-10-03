package dev.prayog.exchange.core;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A deliberately naive matcher used as a test oracle. It keeps every resting order in one list and scans it in full
 * for each match: too slow for real use, but simple enough to check by eye. The real engine must produce exactly the
 * same events.
 */
final class ReferenceMatcher {

    private static final class Resting {
        final long orderId;
        final long accountId;
        final Side side;
        final long price;
        final long arrival;
        long leaves;

        Resting(long orderId, long accountId, Side side, long price, long arrival, long leaves) {
            this.orderId = orderId;
            this.accountId = accountId;
            this.side = side;
            this.price = price;
            this.arrival = arrival;
            this.leaves = leaves;
        }
    }

    private final Map<String, Instrument> instruments;
    private final List<Resting> resting = new ArrayList<>();
    final List<ExchangeEvent> events = new ArrayList<>();
    private long seq = 1;
    private long orderId = 1;
    private long tradeId = 1;
    private long arrival = 0;

    ReferenceMatcher(Map<String, Instrument> instruments) {
        this.instruments = instruments;
    }

    void submit(NewOrder o, long simTime) {
        Instrument instrument = instruments.get(o.symbol());
        RejectReason reason = null;
        if (instrument == null) {
            reason = RejectReason.UNKNOWN_SYMBOL;
        } else if (o.quantity() < 1 || o.quantity() > instrument.maxOrderQuantity()) {
            reason = RejectReason.INVALID_QUANTITY;
        } else if (o.price() < 1) {
            reason = RejectReason.INVALID_PRICE;
        } else if (o.price() % instrument.tickSize() != 0) {
            reason = RejectReason.PRICE_NOT_ON_TICK;
        }
        if (reason != null) {
            events.add(new OrderRejected(seq++, simTime, 0, o.clientOrderId(), o.accountId(), o.symbol(), reason));
            return;
        }
        long id = orderId++;
        events.add(new OrderAccepted(
                seq++,
                simTime,
                id,
                o.clientOrderId(),
                o.accountId(),
                o.symbol(),
                o.side(),
                OrderType.LIMIT,
                o.price(),
                o.quantity()));

        long leaves = o.quantity();
        while (leaves > 0) {
            Resting best = bestCounterparty(o);
            if (best == null) {
                break;
            }
            long qty = Math.min(leaves, best.leaves);
            boolean buy = o.side() == Side.BUY;
            events.add(new Trade(
                    seq++,
                    simTime,
                    tradeId++,
                    o.symbol(),
                    best.price,
                    qty,
                    o.side(),
                    buy ? id : best.orderId,
                    buy ? best.orderId : id,
                    buy ? o.accountId() : best.accountId,
                    buy ? best.accountId : o.accountId()));
            leaves -= qty;
            best.leaves -= qty;
            if (best.leaves == 0) {
                resting.remove(best);
            }
        }
        if (leaves > 0) {
            resting.add(new Resting(id, o.accountId(), o.side(), o.price(), arrival++, leaves));
        }
    }

    /** Best opposite order the incoming one can trade with: best price, then earliest arrival. */
    private Resting bestCounterparty(NewOrder o) {
        Comparator<Resting> byPrice = Comparator.comparingLong(r -> r.price);
        if (o.side() == Side.SELL) {
            byPrice = byPrice.reversed(); // a seller wants the highest bid
        }
        return resting.stream()
                .filter(r -> r.side != o.side())
                .filter(r -> o.side() == Side.BUY ? r.price <= o.price() : r.price >= o.price())
                .min(byPrice.thenComparingLong(r -> r.arrival))
                .orElse(null);
    }
}
