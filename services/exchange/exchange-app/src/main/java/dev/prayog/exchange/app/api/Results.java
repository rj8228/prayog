package dev.prayog.exchange.app.api;

import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.app.api.ApiTypes.FillView;
import dev.prayog.exchange.app.api.ApiTypes.OrderResult;
import dev.prayog.exchange.app.core.CommandResult;
import java.util.ArrayList;
import java.util.List;

/** Turns the events of one command into the answer for the caller who sent it. */
final class Results {

    private Results() {}

    /** For a new order: find its acceptance (or rejection), its fills and any cancel of its remainder. */
    static OrderResult ofNewOrder(CommandResult result, String clientOrderId, String symbol) {
        long orderId = 0;
        long quantity = 0;
        long cancelled = 0;
        long simTime = 0;
        List<FillView> fills = new ArrayList<>();
        String reason = null;
        boolean rejected = false;
        for (ExchangeEvent event : result.events()) {
            simTime = event.simTime();
            if (event instanceof OrderAccepted a) {
                orderId = a.orderId();
                quantity = a.quantity();
            } else if (event instanceof OrderRejected r) {
                rejected = true;
                reason = r.reason().name();
            } else if (event instanceof Trade t && (t.buyOrderId() == orderId || t.sellOrderId() == orderId)) {
                fills.add(new FillView(t.tradeId(), t.price(), t.quantity()));
            } else if (event instanceof OrderCancelled c && c.orderId() == orderId) {
                cancelled += c.cancelledQuantity();
                reason = c.reason().name();
            }
        }
        if (rejected) {
            return new OrderResult(
                    "rejected", 0, clientOrderId, symbol, reason, 0, 0, List.of(), result.inputSeq(), simTime);
        }
        long filled = fills.stream().mapToLong(FillView::quantity).sum();
        long leaves = quantity - filled - cancelled;
        String status = leaves > 0 ? "resting" : cancelled > 0 ? "cancelled" : "filled";
        return new OrderResult(
                status, orderId, clientOrderId, symbol, reason, filled, leaves, fills, result.inputSeq(), simTime);
    }

    /** For a cancel or modify of {@code orderId}. */
    static OrderResult ofChange(CommandResult result, long orderId, String clientOrderId, String symbol) {
        String status = "rejected";
        String reason = null;
        long leaves = 0;
        long simTime = 0;
        List<FillView> fills = new ArrayList<>();
        for (ExchangeEvent event : result.events()) {
            simTime = event.simTime();
            if (event instanceof OrderRejected r) {
                reason = r.reason().name();
            } else if (event instanceof OrderModified m && m.orderId() == orderId) {
                status = "modified";
                leaves = m.leavesQuantity();
            } else if (event instanceof OrderCancelled c && c.orderId() == orderId) {
                status = "cancelled";
                reason = c.reason().name();
                leaves = 0;
            } else if (event instanceof Trade t && (t.buyOrderId() == orderId || t.sellOrderId() == orderId)) {
                fills.add(new FillView(t.tradeId(), t.price(), t.quantity()));
                leaves -= t.quantity();
            }
        }
        long filled = fills.stream().mapToLong(FillView::quantity).sum();
        return new OrderResult(
                status,
                orderId,
                clientOrderId,
                symbol,
                reason,
                filled,
                Math.max(0, leaves),
                fills,
                result.inputSeq(),
                simTime);
    }
}
