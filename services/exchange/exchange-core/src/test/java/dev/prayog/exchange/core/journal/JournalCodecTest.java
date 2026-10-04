package dev.prayog.exchange.core.journal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import dev.prayog.exchange.core.SetSessionState;
import dev.prayog.exchange.core.journal.sbe.CancelReasonCode;
import dev.prayog.exchange.core.journal.sbe.OrderTypeCode;
import dev.prayog.exchange.core.journal.sbe.RejectReasonCode;
import dev.prayog.exchange.core.journal.sbe.SessionStateCode;
import dev.prayog.exchange.core.journal.sbe.SideCode;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;

class JournalCodecTest {

    private final JournalCodec codec = new JournalCodec();
    private final ExpandableArrayBuffer buffer = new ExpandableArrayBuffer(64);

    @Property
    void everyCommandSurvivesARoundTrip(@ForAll("commands") Command command) {
        int length = codec.encode(command, buffer, 0);

        assertThat(codec.decodeCommand(buffer, 0)).isEqualTo(command);
        assertThat(encodeAgain(command, length)).as("same command, same bytes").isTrue();
    }

    @Property
    void everyEventSurvivesARoundTrip(@ForAll("events") ExchangeEvent event) {
        codec.encode(event, buffer, 0);

        assertThat(codec.decodeEvent(buffer, 0)).isEqualTo(event);
    }

    @Property
    void engineSetupSurvivesARoundTrip(@ForAll("setups") EngineSetup setup) {
        codec.encode(setup, buffer, 0);

        assertThat(codec.isEngineSetup(buffer, 0)).isTrue();
        assertThat(codec.decodeEngineSetup(buffer, 0)).isEqualTo(setup);
    }

    @Test
    void decodesAtANonZeroOffset() {
        NewOrder order = new NewOrder("c-1", 7, "INFY", Side.BUY, OrderType.LIMIT, 150_000, 10);
        codec.encode(order, buffer, 37);

        assertThat(codec.decodeCommand(buffer, 37)).isEqualTo(order);
    }

    /**
     * Pins the exact bytes. If this fails, the layout changed: old journals no longer replay to the same checksum.
     * Change the schema only by adding fields at the end and bumping its version (see journal-schema.xml).
     */
    @Test
    void layoutIsPinned() {
        NewOrder order = new NewOrder("c1", 7, "INFY", Side.SELL, OrderType.LIMIT, 150_000, 10);
        int length = codec.encode(order, buffer, 0);

        assertThat(HexFormat.of().formatHex(buffer.byteArray(), 0, length))
                .isEqualTo("1a00" + "0100" + "0100" + "0000" // header: block length 26, message 1, schema 1, v0
                        + "0700000000000000" // accountId 7
                        + "01" // side SELL
                        + "00" // type LIMIT
                        + "f049020000000000" // price 150000
                        + "0a00000000000000" // quantity 10
                        + "02000000" + "6331" // clientOrderId "c1"
                        + "04000000" + "494e4659"); // symbol "INFY"
    }

    @Test
    void everyJavaEnumValueHasAWireCode() {
        assertSameNames(Side.values(), SideCode.values());
        assertSameNames(OrderType.values(), OrderTypeCode.values());
        assertSameNames(SessionState.values(), SessionStateCode.values());
        assertSameNames(CancelReason.values(), CancelReasonCode.values());
        assertSameNames(RejectReason.values(), RejectReasonCode.values());
    }

    @Test
    void rejectsAForeignSchema() {
        UnsafeBuffer foreign = new UnsafeBuffer(new byte[16]);
        foreign.putShort(4, (short) 99); // schema id

        assertThatThrownBy(() -> codec.decodeCommand(foreign, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema id 99");
    }

    @Test
    void anEventIsNotACommand() {
        codec.encode(new SessionStateChanged(1, 2, SessionState.OPEN), buffer, 0);

        assertThatThrownBy(() -> codec.decodeCommand(buffer, 0)).hasMessageContaining("not a command");
    }

    private boolean encodeAgain(Command command, int length) {
        byte[] first = Arrays.copyOf(buffer.byteArray(), length);
        ExpandableArrayBuffer other = new ExpandableArrayBuffer(8);
        int otherLength = new JournalCodec().encode(command, other, 0);
        return otherLength == length && Arrays.equals(first, Arrays.copyOf(other.byteArray(), otherLength));
    }

    private static <A extends Enum<A>, B extends Enum<B>> void assertSameNames(A[] java, B[] wire) {
        List<String> wireNames = Arrays.stream(wire)
                .map(Enum::name)
                .filter(name -> !name.equals("NULL_VAL"))
                .toList();
        assertThat(wireNames)
                .containsExactlyElementsOf(Arrays.stream(java).map(Enum::name).toList());
    }

    // ---- generators ------------------------------------------------------------------------------------------------

    private static Arbitrary<String> text() {
        // Any Unicode, including empty and multi-byte characters: SBE strings are length-prefixed UTF-8.
        return Arbitraries.strings().ofMaxLength(40);
    }

    private static Arbitrary<Long> anyLong() {
        return Arbitraries.longs();
    }

    @Provide
    Arbitrary<Command> commands() {
        Arbitrary<Side> side = Arbitraries.of(Side.class);
        Arbitrary<OrderType> type = Arbitraries.of(OrderType.class);
        return Arbitraries.oneOf(
                Combinators.combine(text(), anyLong(), text(), side, type, anyLong(), anyLong())
                        .as(NewOrder::new),
                Combinators.combine(text(), anyLong(), text(), anyLong()).as(CancelOrder::new),
                Combinators.combine(text(), anyLong(), text(), anyLong(), anyLong(), anyLong())
                        .as(ModifyOrder::new),
                anyLong().map(ClockTick::new),
                Arbitraries.of(SessionState.class).map(SetSessionState::new),
                Combinators.combine(anyLong(), Arbitraries.of(true, false)).as(SetAccountEnabled::new));
    }

    @Provide
    Arbitrary<ExchangeEvent> events() {
        Arbitrary<Side> side = Arbitraries.of(Side.class);
        Arbitrary<OrderAccepted> accepted = Combinators.combine(
                        anyLong(), anyLong(), anyLong(), text(), anyLong(), text(), side)
                .flatAs((seq, time, orderId, clientId, account, symbol, s) -> Combinators.combine(
                                Arbitraries.of(OrderType.class), anyLong(), anyLong())
                        .as((type, price, qty) ->
                                new OrderAccepted(seq, time, orderId, clientId, account, symbol, s, type, price, qty)));
        Arbitrary<OrderRejected> rejected = Combinators.combine(
                        anyLong(), anyLong(), anyLong(), text(), anyLong(), text(), Arbitraries.of(RejectReason.class))
                .as(OrderRejected::new);
        Arbitrary<OrderCancelled> cancelled = Combinators.combine(
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        anyLong(),
                        text(),
                        anyLong(),
                        Arbitraries.of(CancelReason.class))
                .as(OrderCancelled::new);
        Arbitrary<OrderModified> modified = Combinators.combine(
                        anyLong(), anyLong(), anyLong(), anyLong(), text(), anyLong(), anyLong(), anyLong())
                .as(OrderModified::new);
        Arbitrary<Trade> trade = Combinators.combine(anyLong(), anyLong(), anyLong(), text(), anyLong(), anyLong())
                .flatAs((seq, time, tradeId, symbol, price, qty) -> Combinators.combine(
                                side, anyLong(), anyLong(), anyLong(), anyLong())
                        .as((aggressor, buyId, sellId, buyAcc, sellAcc) -> new Trade(
                                seq, time, tradeId, symbol, price, qty, aggressor, buyId, sellId, buyAcc, sellAcc)));
        Arbitrary<BookUpdate> book = Combinators.combine(
                        anyLong(), anyLong(), text(), side, anyLong(), anyLong(), Arbitraries.integers())
                .as(BookUpdate::new);
        Arbitrary<SessionStateChanged> session = Combinators.combine(
                        anyLong(), anyLong(), Arbitraries.of(SessionState.class))
                .as(SessionStateChanged::new);
        return Arbitraries.oneOf(accepted, rejected, cancelled, modified, trade, book, session);
    }

    @Provide
    Arbitrary<EngineSetup> setups() {
        Arbitrary<Instrument> instrument = Combinators.combine(
                        Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(12),
                        Arbitraries.longs().between(1, 100),
                        Arbitraries.longs().between(1, 1_000_000),
                        Arbitraries.longs().between(10_000, 10_000_000),
                        Arbitraries.integers().between(1, 20))
                .as(Instrument::new);
        Arbitrary<SessionSchedule> schedule = Combinators.combine(
                        Arbitraries.longs().between(0, 43_199_999_999_999L),
                        Arbitraries.longs().between(43_200_000_000_000L, 86_399_999_999_999L),
                        Arbitraries.integers().between(-18 * 3600, 18 * 3600))
                .as((open, close, offset) -> new SessionSchedule(
                        LocalTime.ofNanoOfDay(open), LocalTime.ofNanoOfDay(close), ZoneOffset.ofTotalSeconds(offset)));
        return Combinators.combine(instrument.list().ofMaxSize(5), schedule.injectNull(0.3))
                .as(EngineSetup::new);
    }
}
