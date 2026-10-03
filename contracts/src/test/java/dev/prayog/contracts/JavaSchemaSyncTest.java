package dev.prayog.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.event.ExchangeEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Fails when a Java type and its JSON Schema drift apart, so a change has to be made in both places. */
class JavaSchemaSyncTest {

    private final JsonNode common = Schemas.tree("common").get("$defs");
    private final JsonNode events = Schemas.tree("events/exchange-event");

    @Test
    void enumsMatchSchema() {
        assertEnum(Side.class, "side");
        assertEnum(OrderType.class, "orderType");
        assertEnum(SessionState.class, "sessionState");
        assertEnum(RejectReason.class, "rejectReason");
        assertEnum(CancelReason.class, "cancelReason");
    }

    @Test
    void everyEventRecordIsInTheSchema() {
        Set<String> javaTypes = Arrays.stream(ExchangeEvent.class.getPermittedSubclasses())
                .map(Class::getSimpleName)
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(strings(events.get("properties").get("type").get("enum"))).isEqualTo(javaTypes);
    }

    @Test
    void eventRecordFieldsMatchSchemaProperties() {
        for (Class<?> type : ExchangeEvent.class.getPermittedSubclasses()) {
            Set<String> javaFields = Arrays.stream(type.getRecordComponents())
                    .map(c -> c.getName())
                    .collect(Collectors.toCollection(TreeSet::new));
            Set<String> schemaFields = new TreeSet<>(events.get("$defs")
                    .get(type.getSimpleName())
                    .get("properties")
                    .propertyNames());
            schemaFields.remove("type"); // the discriminator is the record's class, not a field
            assertThat(javaFields).as(type.getSimpleName()).isEqualTo(schemaFields);
        }
    }

    private void assertEnum(Class<? extends Enum<?>> type, String def) {
        Set<String> javaValues =
                Arrays.stream(type.getEnumConstants()).map(Enum::name).collect(Collectors.toCollection(TreeSet::new));
        assertThat(strings(common.get(def).get("enum"))).as(def).isEqualTo(javaValues);
    }

    private static Set<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.asString()));
        return new TreeSet<>(values);
    }
}
