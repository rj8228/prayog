package dev.prayog.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.InputFormat;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.EventJson;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderModified;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.contracts.event.Trade;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class EventJsonTest {

    static final List<ExchangeEvent> ONE_OF_EACH = List.of(
            new OrderAccepted(1, 1_000, 7, "c-1", 42, "INFY", Side.BUY, OrderType.LIMIT, 150_000, 10),
            new OrderAccepted(2, 1_000, 8, "c-2", 42, "INFY", Side.SELL, OrderType.MARKET, 0, 3),
            new OrderRejected(3, 1_000, 0, "c-3", 42, "TCS", RejectReason.PRICE_OUTSIDE_BAND),
            new OrderCancelled(4, 2_000, 7, 42, "INFY", 4, CancelReason.CLIENT_REQUEST),
            new OrderModified(5, 2_000, 7, 42, "INFY", 150_005, 6, 6),
            new Trade(6, 3_000, 1, "INFY", 150_000, 2, Side.SELL, 7, 8, 42, 43),
            new BookUpdate(7, 3_000, "INFY", Side.BUY, 150_000, 0, 0),
            new SessionStateChanged(8, 4_000, SessionState.HALTED));

    @Test
    void coversEveryEventType() {
        Set<Class<?>> covered = ONE_OF_EACH.stream().map(Object::getClass).collect(Collectors.toSet());
        assertThat(covered).containsExactlyInAnyOrder(ExchangeEvent.class.getPermittedSubclasses());
    }

    @Test
    void everyEventIsValidAgainstTheSchemaAndRoundTrips() {
        var schema = Schemas.load("events/exchange-event");
        for (ExchangeEvent event : ONE_OF_EACH) {
            String json = new String(EventJson.toBytes(event), StandardCharsets.UTF_8);
            assertThat(schema.validate(json, InputFormat.JSON)).as(json).isEmpty();
            assertThat(json)
                    .as("type comes first, for people reading the topic")
                    .startsWith("{\"type\":");
            assertThat(EventJson.fromBytes(json.getBytes(StandardCharsets.UTF_8)))
                    .isEqualTo(event);
        }
    }

    @Test
    void theKeyIsTheSymbolAndEmptyForSessionWideEvents() {
        assertThat(EventJson.key(ONE_OF_EACH.get(5))).isEqualTo("INFY");
        assertThat(EventJson.key(ONE_OF_EACH.get(2))).isEqualTo("TCS");
        assertThat(EventJson.key(ONE_OF_EACH.get(7))).isEmpty();
    }

    @Test
    void readersTolerateFieldsAddedLater() {
        String json = "{\"type\":\"SessionStateChanged\",\"seq\":9,\"simTime\":1,\"state\":\"OPEN\",\"reason\":\"x\"}";
        assertThat(EventJson.fromBytes(json.getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(new SessionStateChanged(9, 1, SessionState.OPEN));
    }

    @Test
    void anUnknownTypeIsRefused() {
        byte[] json = "{\"type\":\"Nope\",\"seq\":1,\"simTime\":1}".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> EventJson.fromBytes(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Nope");
        assertThat(Arrays.asList(ExchangeEvent.class.getPermittedSubclasses())).hasSize(7);
    }
}
