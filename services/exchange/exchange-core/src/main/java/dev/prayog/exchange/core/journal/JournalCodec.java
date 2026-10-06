package dev.prayog.exchange.core.journal;

import dev.prayog.contracts.CancelReason;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.RejectReason;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.ClockTick;
import dev.prayog.exchange.core.Command;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.ModifyOrder;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.SetAccountEnabled;
import dev.prayog.exchange.core.SetRules;
import dev.prayog.exchange.core.SetSessionState;
import dev.prayog.exchange.core.TakeSnapshot;
import dev.prayog.exchange.core.journal.sbe.BookUpdateDecoder;
import dev.prayog.exchange.core.journal.sbe.BookUpdateEncoder;
import dev.prayog.exchange.core.journal.sbe.BooleanCode;
import dev.prayog.exchange.core.journal.sbe.CancelOrderDecoder;
import dev.prayog.exchange.core.journal.sbe.CancelOrderEncoder;
import dev.prayog.exchange.core.journal.sbe.CancelReasonCode;
import dev.prayog.exchange.core.journal.sbe.ClockTickDecoder;
import dev.prayog.exchange.core.journal.sbe.ClockTickEncoder;
import dev.prayog.exchange.core.journal.sbe.MessageHeaderDecoder;
import dev.prayog.exchange.core.journal.sbe.MessageHeaderEncoder;
import dev.prayog.exchange.core.journal.sbe.ModifyOrderDecoder;
import dev.prayog.exchange.core.journal.sbe.ModifyOrderEncoder;
import dev.prayog.exchange.core.journal.sbe.NewOrderDecoder;
import dev.prayog.exchange.core.journal.sbe.NewOrderEncoder;
import dev.prayog.exchange.core.journal.sbe.OrderAcceptedDecoder;
import dev.prayog.exchange.core.journal.sbe.OrderAcceptedEncoder;
import dev.prayog.exchange.core.journal.sbe.OrderCancelledDecoder;
import dev.prayog.exchange.core.journal.sbe.OrderCancelledEncoder;
import dev.prayog.exchange.core.journal.sbe.OrderModifiedDecoder;
import dev.prayog.exchange.core.journal.sbe.OrderModifiedEncoder;
import dev.prayog.exchange.core.journal.sbe.OrderRejectedDecoder;
import dev.prayog.exchange.core.journal.sbe.OrderRejectedEncoder;
import dev.prayog.exchange.core.journal.sbe.OrderTypeCode;
import dev.prayog.exchange.core.journal.sbe.RejectReasonCode;
import dev.prayog.exchange.core.journal.sbe.SessionStartDecoder;
import dev.prayog.exchange.core.journal.sbe.SessionStartEncoder;
import dev.prayog.exchange.core.journal.sbe.SessionStateChangedDecoder;
import dev.prayog.exchange.core.journal.sbe.SessionStateChangedEncoder;
import dev.prayog.exchange.core.journal.sbe.SessionStateCode;
import dev.prayog.exchange.core.journal.sbe.SetAccountEnabledDecoder;
import dev.prayog.exchange.core.journal.sbe.SetAccountEnabledEncoder;
import dev.prayog.exchange.core.journal.sbe.SetRulesDecoder;
import dev.prayog.exchange.core.journal.sbe.SetRulesEncoder;
import dev.prayog.exchange.core.journal.sbe.SetSessionStateDecoder;
import dev.prayog.exchange.core.journal.sbe.SetSessionStateEncoder;
import dev.prayog.exchange.core.journal.sbe.SideCode;
import dev.prayog.exchange.core.journal.sbe.TakeSnapshotDecoder;
import dev.prayog.exchange.core.journal.sbe.TakeSnapshotEncoder;
import dev.prayog.exchange.core.journal.sbe.TradeDecoder;
import dev.prayog.exchange.core.journal.sbe.TradeEncoder;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;

/**
 * Turns commands, events and the {@link EngineSetup} into SBE messages and back. The layout is defined in
 * {@code src/main/resources/sbe/journal-schema.xml}; SBE generates the flyweight encoders and decoders used here.
 *
 * <p>Each message starts with SBE's 8-byte header (fixed-part length, message id, schema id, schema version), so a
 * record says what it is without any extra type field.
 *
 * <p>Encoders and decoders are flyweights: reusable views over a buffer, holding a position. So an instance is
 * <em>not</em> thread-safe; each thread (the journal stage, a replay) owns its own codec.
 */
public final class JournalCodec {

    private final MessageHeaderEncoder headerEncoder = new MessageHeaderEncoder();
    private final MessageHeaderDecoder headerDecoder = new MessageHeaderDecoder();

    private final NewOrderEncoder newOrderEncoder = new NewOrderEncoder();
    private final CancelOrderEncoder cancelOrderEncoder = new CancelOrderEncoder();
    private final ModifyOrderEncoder modifyOrderEncoder = new ModifyOrderEncoder();
    private final ClockTickEncoder clockTickEncoder = new ClockTickEncoder();
    private final SetSessionStateEncoder setSessionStateEncoder = new SetSessionStateEncoder();
    private final SetAccountEnabledEncoder setAccountEnabledEncoder = new SetAccountEnabledEncoder();
    private final SetRulesEncoder setRulesEncoder = new SetRulesEncoder();
    private final TakeSnapshotEncoder takeSnapshotEncoder = new TakeSnapshotEncoder();

    private final NewOrderDecoder newOrderDecoder = new NewOrderDecoder();
    private final CancelOrderDecoder cancelOrderDecoder = new CancelOrderDecoder();
    private final ModifyOrderDecoder modifyOrderDecoder = new ModifyOrderDecoder();
    private final ClockTickDecoder clockTickDecoder = new ClockTickDecoder();
    private final SetSessionStateDecoder setSessionStateDecoder = new SetSessionStateDecoder();
    private final SetAccountEnabledDecoder setAccountEnabledDecoder = new SetAccountEnabledDecoder();
    private final SetRulesDecoder setRulesDecoder = new SetRulesDecoder();

    private final OrderAcceptedEncoder orderAcceptedEncoder = new OrderAcceptedEncoder();
    private final OrderRejectedEncoder orderRejectedEncoder = new OrderRejectedEncoder();
    private final OrderCancelledEncoder orderCancelledEncoder = new OrderCancelledEncoder();
    private final OrderModifiedEncoder orderModifiedEncoder = new OrderModifiedEncoder();
    private final TradeEncoder tradeEncoder = new TradeEncoder();
    private final BookUpdateEncoder bookUpdateEncoder = new BookUpdateEncoder();
    private final SessionStateChangedEncoder sessionStateChangedEncoder = new SessionStateChangedEncoder();

    private final OrderAcceptedDecoder orderAcceptedDecoder = new OrderAcceptedDecoder();
    private final OrderRejectedDecoder orderRejectedDecoder = new OrderRejectedDecoder();
    private final OrderCancelledDecoder orderCancelledDecoder = new OrderCancelledDecoder();
    private final OrderModifiedDecoder orderModifiedDecoder = new OrderModifiedDecoder();
    private final TradeDecoder tradeDecoder = new TradeDecoder();
    private final BookUpdateDecoder bookUpdateDecoder = new BookUpdateDecoder();
    private final SessionStateChangedDecoder sessionStateChangedDecoder = new SessionStateChangedDecoder();

    private final SessionStartEncoder sessionStartEncoder = new SessionStartEncoder();
    private final SessionStartDecoder sessionStartDecoder = new SessionStartDecoder();

    /** The message id (SBE template id) of the message at {@code offset}, after checking it is our schema. */
    public int templateId(DirectBuffer buffer, int offset) {
        headerDecoder.wrap(buffer, offset);
        if (headerDecoder.schemaId() != MessageHeaderDecoder.SCHEMA_ID) {
            throw new IllegalArgumentException("not a Prayog journal message: schema id " + headerDecoder.schemaId());
        }
        return headerDecoder.templateId();
    }

    /** Whether the message at {@code offset} is the {@link EngineSetup} record. */
    public boolean isEngineSetup(DirectBuffer buffer, int offset) {
        return templateId(buffer, offset) == SessionStartDecoder.TEMPLATE_ID;
    }

    // ---- commands --------------------------------------------------------------------------------------------------

    /** Writes {@code command} at {@code offset}. Returns the number of bytes written (header included). */
    public int encode(Command command, MutableDirectBuffer buffer, int offset) {
        int bodyLength = switch (command) {
            case NewOrder c ->
                newOrderEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .accountId(c.accountId())
                        .side(SideCode.valueOf(c.side().name()))
                        .orderType(OrderTypeCode.valueOf(c.type().name()))
                        .price(c.price())
                        .quantity(c.quantity())
                        .clientOrderId(c.clientOrderId())
                        .symbol(c.symbol())
                        .encodedLength();
            case CancelOrder c ->
                cancelOrderEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .accountId(c.accountId())
                        .orderId(c.orderId())
                        .clientOrderId(c.clientOrderId())
                        .symbol(c.symbol())
                        .encodedLength();
            case ModifyOrder c ->
                modifyOrderEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .accountId(c.accountId())
                        .orderId(c.orderId())
                        .price(c.price())
                        .quantity(c.quantity())
                        .clientOrderId(c.clientOrderId())
                        .symbol(c.symbol())
                        .encodedLength();
            case ClockTick c ->
                clockTickEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .simTime(c.simTime())
                        .encodedLength();
            case SetSessionState c ->
                setSessionStateEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .state(SessionStateCode.valueOf(c.state().name()))
                        .encodedLength();
            case SetAccountEnabled c ->
                setAccountEnabledEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .accountId(c.accountId())
                        .enabled(c.enabled() ? BooleanCode.TRUE : BooleanCode.FALSE)
                        .encodedLength();
            case SetRules c ->
                setRulesEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .version(c.version())
                        .encodedLength();
            case TakeSnapshot c ->
                takeSnapshotEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .encodedLength();
        };
        return MessageHeaderEncoder.ENCODED_LENGTH + bodyLength;
    }

    /** Reads the command at {@code offset}. */
    public Command decodeCommand(DirectBuffer buffer, int offset) {
        int templateId = templateId(buffer, offset);
        // Strings must be read in schema order: each variable-length field starts where the previous one ended.
        return switch (templateId) {
            case NewOrderDecoder.TEMPLATE_ID -> {
                NewOrderDecoder d = newOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                long accountId = d.accountId();
                Side side = Side.valueOf(d.side().name());
                OrderType type = OrderType.valueOf(d.orderType().name());
                long price = d.price();
                long quantity = d.quantity();
                String clientOrderId = d.clientOrderId();
                yield new NewOrder(clientOrderId, accountId, d.symbol(), side, type, price, quantity);
            }
            case CancelOrderDecoder.TEMPLATE_ID -> {
                CancelOrderDecoder d = cancelOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                long accountId = d.accountId();
                long orderId = d.orderId();
                String clientOrderId = d.clientOrderId();
                yield new CancelOrder(clientOrderId, accountId, d.symbol(), orderId);
            }
            case ModifyOrderDecoder.TEMPLATE_ID -> {
                ModifyOrderDecoder d = modifyOrderDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                long accountId = d.accountId();
                long orderId = d.orderId();
                long price = d.price();
                long quantity = d.quantity();
                String clientOrderId = d.clientOrderId();
                yield new ModifyOrder(clientOrderId, accountId, d.symbol(), orderId, price, quantity);
            }
            case ClockTickDecoder.TEMPLATE_ID ->
                new ClockTick(clockTickDecoder
                        .wrapAndApplyHeader(buffer, offset, headerDecoder)
                        .simTime());
            case SetSessionStateDecoder.TEMPLATE_ID ->
                new SetSessionState(SessionState.valueOf(setSessionStateDecoder
                        .wrapAndApplyHeader(buffer, offset, headerDecoder)
                        .state()
                        .name()));
            case SetAccountEnabledDecoder.TEMPLATE_ID -> {
                SetAccountEnabledDecoder d = setAccountEnabledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new SetAccountEnabled(d.accountId(), d.enabled() == BooleanCode.TRUE);
            }
            case SetRulesDecoder.TEMPLATE_ID ->
                new SetRules(setRulesDecoder
                        .wrapAndApplyHeader(buffer, offset, headerDecoder)
                        .version());
            case TakeSnapshotDecoder.TEMPLATE_ID -> new TakeSnapshot();
            default -> throw new IllegalArgumentException("not a command: message id " + templateId);
        };
    }

    // ---- events ----------------------------------------------------------------------------------------------------

    /** Writes {@code event} at {@code offset}. Returns the number of bytes written (header included). */
    public int encode(ExchangeEvent event, MutableDirectBuffer buffer, int offset) {
        int bodyLength = switch (event) {
            case OrderAccepted e ->
                orderAcceptedEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .orderId(e.orderId())
                        .accountId(e.accountId())
                        .side(SideCode.valueOf(e.side().name()))
                        .orderType(OrderTypeCode.valueOf(e.orderType().name()))
                        .price(e.price())
                        .quantity(e.quantity())
                        .clientOrderId(e.clientOrderId())
                        .symbol(e.symbol())
                        .encodedLength();
            case OrderRejected e ->
                orderRejectedEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .orderId(e.orderId())
                        .accountId(e.accountId())
                        .reason(RejectReasonCode.valueOf(e.reason().name()))
                        .clientOrderId(e.clientOrderId())
                        .symbol(e.symbol())
                        .encodedLength();
            case OrderCancelled e ->
                orderCancelledEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .orderId(e.orderId())
                        .accountId(e.accountId())
                        .cancelledQuantity(e.cancelledQuantity())
                        .reason(CancelReasonCode.valueOf(e.reason().name()))
                        .symbol(e.symbol())
                        .encodedLength();
            case OrderModified e ->
                orderModifiedEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .orderId(e.orderId())
                        .accountId(e.accountId())
                        .price(e.price())
                        .quantity(e.quantity())
                        .leavesQuantity(e.leavesQuantity())
                        .symbol(e.symbol())
                        .encodedLength();
            case Trade e ->
                tradeEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .tradeId(e.tradeId())
                        .price(e.price())
                        .quantity(e.quantity())
                        .aggressorSide(SideCode.valueOf(e.aggressorSide().name()))
                        .buyOrderId(e.buyOrderId())
                        .sellOrderId(e.sellOrderId())
                        .buyAccountId(e.buyAccountId())
                        .sellAccountId(e.sellAccountId())
                        .symbol(e.symbol())
                        .encodedLength();
            case BookUpdate e ->
                bookUpdateEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .side(SideCode.valueOf(e.side().name()))
                        .price(e.price())
                        .quantity(e.quantity())
                        .orderCount(e.orderCount())
                        .symbol(e.symbol())
                        .encodedLength();
            case SessionStateChanged e ->
                sessionStateChangedEncoder
                        .wrapAndApplyHeader(buffer, offset, headerEncoder)
                        .seq(e.seq())
                        .simTime(e.simTime())
                        .state(SessionStateCode.valueOf(e.state().name()))
                        .encodedLength();
        };
        return MessageHeaderEncoder.ENCODED_LENGTH + bodyLength;
    }

    /** Reads the event at {@code offset}. */
    public ExchangeEvent decodeEvent(DirectBuffer buffer, int offset) {
        int templateId = templateId(buffer, offset);
        return switch (templateId) {
            case OrderAcceptedDecoder.TEMPLATE_ID -> {
                OrderAcceptedDecoder d = orderAcceptedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                long seq = d.seq();
                long simTime = d.simTime();
                long orderId = d.orderId();
                long accountId = d.accountId();
                Side side = Side.valueOf(d.side().name());
                OrderType type = OrderType.valueOf(d.orderType().name());
                long price = d.price();
                long quantity = d.quantity();
                String clientOrderId = d.clientOrderId();
                yield new OrderAccepted(
                        seq, simTime, orderId, clientOrderId, accountId, d.symbol(), side, type, price, quantity);
            }
            case OrderRejectedDecoder.TEMPLATE_ID -> {
                OrderRejectedDecoder d = orderRejectedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                long seq = d.seq();
                long simTime = d.simTime();
                long orderId = d.orderId();
                long accountId = d.accountId();
                RejectReason reason = RejectReason.valueOf(d.reason().name());
                String clientOrderId = d.clientOrderId();
                yield new OrderRejected(seq, simTime, orderId, clientOrderId, accountId, d.symbol(), reason);
            }
            case OrderCancelledDecoder.TEMPLATE_ID -> {
                OrderCancelledDecoder d = orderCancelledDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new OrderCancelled(
                        d.seq(),
                        d.simTime(),
                        d.orderId(),
                        d.accountId(),
                        d.symbol(),
                        d.cancelledQuantity(),
                        CancelReason.valueOf(d.reason().name()));
            }
            case OrderModifiedDecoder.TEMPLATE_ID -> {
                OrderModifiedDecoder d = orderModifiedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new OrderModified(
                        d.seq(),
                        d.simTime(),
                        d.orderId(),
                        d.accountId(),
                        d.symbol(),
                        d.price(),
                        d.quantity(),
                        d.leavesQuantity());
            }
            case TradeDecoder.TEMPLATE_ID -> {
                TradeDecoder d = tradeDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new Trade(
                        d.seq(),
                        d.simTime(),
                        d.tradeId(),
                        d.symbol(),
                        d.price(),
                        d.quantity(),
                        Side.valueOf(d.aggressorSide().name()),
                        d.buyOrderId(),
                        d.sellOrderId(),
                        d.buyAccountId(),
                        d.sellAccountId());
            }
            case BookUpdateDecoder.TEMPLATE_ID -> {
                BookUpdateDecoder d = bookUpdateDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new BookUpdate(
                        d.seq(),
                        d.simTime(),
                        d.symbol(),
                        Side.valueOf(d.side().name()),
                        d.price(),
                        d.quantity(),
                        d.orderCount());
            }
            case SessionStateChangedDecoder.TEMPLATE_ID -> {
                SessionStateChangedDecoder d =
                        sessionStateChangedDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
                yield new SessionStateChanged(
                        d.seq(), d.simTime(), SessionState.valueOf(d.state().name()));
            }
            default -> throw new IllegalArgumentException("not an event: message id " + templateId);
        };
    }

    // ---- engine setup ----------------------------------------------------------------------------------------------

    /** Writes {@code setup} at {@code offset}. Returns the number of bytes written (header included). */
    public int encode(EngineSetup setup, MutableDirectBuffer buffer, int offset) {
        SessionSchedule schedule = setup.schedule();
        SessionStartEncoder e = sessionStartEncoder.wrapAndApplyHeader(buffer, offset, headerEncoder);
        if (schedule == null) {
            e.hasSchedule(BooleanCode.FALSE).openNanoOfDay(0).closeNanoOfDay(0).offsetSeconds(0);
        } else {
            e.hasSchedule(BooleanCode.TRUE)
                    .openNanoOfDay(schedule.open().toNanoOfDay())
                    .closeNanoOfDay(schedule.close().toNanoOfDay())
                    .offsetSeconds(schedule.offset().getTotalSeconds());
        }
        SessionStartEncoder.InstrumentsEncoder group =
                e.instrumentsCount(setup.instruments().size());
        for (Instrument instrument : setup.instruments()) {
            group.next()
                    .tickSize(instrument.tickSize())
                    .maxOrderQuantity(instrument.maxOrderQuantity())
                    .referencePrice(instrument.referencePrice())
                    .bandPercent(instrument.bandPercent())
                    .symbol(instrument.symbol());
        }
        return MessageHeaderEncoder.ENCODED_LENGTH + e.encodedLength();
    }

    /** Reads the {@link EngineSetup} at {@code offset}. */
    public EngineSetup decodeEngineSetup(DirectBuffer buffer, int offset) {
        int templateId = templateId(buffer, offset);
        if (templateId != SessionStartDecoder.TEMPLATE_ID) {
            throw new IllegalArgumentException("not an engine setup: message id " + templateId);
        }
        SessionStartDecoder d = sessionStartDecoder.wrapAndApplyHeader(buffer, offset, headerDecoder);
        SessionSchedule schedule = d.hasSchedule() == BooleanCode.TRUE
                ? new SessionSchedule(
                        LocalTime.ofNanoOfDay(d.openNanoOfDay()),
                        LocalTime.ofNanoOfDay(d.closeNanoOfDay()),
                        ZoneOffset.ofTotalSeconds(d.offsetSeconds()))
                : null;
        List<Instrument> instruments = new ArrayList<>();
        for (SessionStartDecoder.InstrumentsDecoder g : d.instruments()) {
            long tickSize = g.tickSize();
            long maxOrderQuantity = g.maxOrderQuantity();
            long referencePrice = g.referencePrice();
            int bandPercent = g.bandPercent();
            instruments.add(new Instrument(g.symbol(), tickSize, maxOrderQuantity, referencePrice, bandPercent));
        }
        return new EngineSetup(instruments, schedule);
    }
}
