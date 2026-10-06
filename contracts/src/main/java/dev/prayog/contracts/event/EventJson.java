package dev.prayog.contracts.event;

import java.util.Map;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The JSON form of exchange events on Kafka ({@code contracts/schemas/events/exchange-event.schema.json}): the record's
 * fields plus a {@code type} discriminator, which comes first so a person reading the topic sees it at once. Writer
 * (exchange) and readers (post-trade) share this class so they cannot drift apart.
 *
 * <p>Readers ignore fields they do not know: a producer may add an optional field without breaking older consumers.
 * Removing or renaming a field needs a new topic version ({@code .v2}).
 */
public final class EventJson {

    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private static final Map<String, Class<? extends ExchangeEvent>> TYPES = Map.of(
            "OrderAccepted", OrderAccepted.class,
            "OrderRejected", OrderRejected.class,
            "OrderCancelled", OrderCancelled.class,
            "OrderModified", OrderModified.class,
            "Trade", Trade.class,
            "BookUpdate", BookUpdate.class,
            "SessionStateChanged", SessionStateChanged.class);

    /**
     * Fields the schema leaves out when they do not apply, which the Java records hold as 0: a market order's price,
     * and the order ID of a rejected request that named no existing order.
     */
    private static final Map<Class<?>, String> OPTIONAL =
            Map.of(OrderAccepted.class, "price", OrderRejected.class, "orderId");

    private EventJson() {}

    /** UTF-8 JSON of {@code event}. */
    public static byte[] toBytes(ExchangeEvent event) {
        ObjectNode node = JSON.createObjectNode();
        node.put("type", event.getClass().getSimpleName());
        node.setAll((ObjectNode) JSON.valueToTree(event));
        String optional = OPTIONAL.get(event.getClass());
        if (optional != null && node.path(optional).asLong() == 0) {
            node.remove(optional);
        }
        return JSON.writeValueAsBytes(node);
    }

    /** Parses one event; throws {@link IllegalArgumentException} for an unknown {@code type}. */
    public static ExchangeEvent fromBytes(byte[] json) {
        JsonNode node = JSON.readTree(json);
        String type = node.path("type").asString("");
        Class<? extends ExchangeEvent> cls = TYPES.get(type);
        if (cls == null) {
            throw new IllegalArgumentException("unknown event type: '" + type + "'");
        }
        String optional = OPTIONAL.get(cls);
        if (optional != null && !node.has(optional)) {
            ((ObjectNode) node).put(optional, 0L);
        }
        return JSON.treeToValue(node, cls);
    }

    /**
     * The Kafka record key: the symbol, so one symbol's events stay in order on one partition. Session-wide events
     * have no symbol and use the empty key (one fixed partition).
     */
    public static String key(ExchangeEvent event) {
        return switch (event) {
            case OrderAccepted e -> e.symbol();
            case OrderRejected e -> e.symbol();
            case OrderCancelled e -> e.symbol();
            case OrderModified e -> e.symbol();
            case Trade e -> e.symbol();
            case BookUpdate e -> e.symbol();
            case SessionStateChanged e -> "";
        };
    }
}
