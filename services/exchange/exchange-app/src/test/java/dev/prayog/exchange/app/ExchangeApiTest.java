package dev.prayog.exchange.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The exchange as a client sees it: a real Spring context with a real journal in a temp directory. Tokens are
 * simulated with {@code mockJwt} (signature checks are Spring Security's job and are exercised against the real
 * Keycloak by {@code make e2e}); everything after authentication is the real code path.
 */
class ExchangeApiTest {

    @TempDir
    Path journal;

    private ConfigurableApplicationContext context;
    private WebTestClient client;

    @BeforeEach
    void start() {
        context = startApp(journal);
        client = clientFor(context);
        awaitOpen();
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void anOrderRestsIsListedAndCanBeCancelled() {
        JsonNode placed = place(
                trader("alice"),
                null,
                Map.of("symbol", "INFY", "side", "BUY", "type", "LIMIT", "price", 149_000, "quantity", 10));
        assertThat(placed.get("status").asString()).isEqualTo("resting");
        long orderId = placed.get("orderId").asLong();

        JsonNode open = get(trader("alice"), "/api/v1/orders");
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("orderId").asLong()).isEqualTo(orderId);

        JsonNode cancelled = client.mutateWith(trader("alice"))
                .delete()
                .uri("/api/v1/orders/" + orderId)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(cancelled.get("status").asString()).isEqualTo("cancelled");
        assertThat(get(trader("alice"), "/api/v1/orders")).isEmpty();
    }

    @Test
    void twoAccountsTradeAndTheMarketDataShowsIt() {
        place(
                trader("alice"),
                null,
                Map.of("symbol", "TCS", "side", "SELL", "type", "LIMIT", "price", 400_000, "quantity", 10));
        JsonNode buy = place(
                trader("bob"),
                null,
                Map.of("symbol", "TCS", "side", "BUY", "type", "LIMIT", "price", 400_500, "quantity", 4));

        assertThat(buy.get("status").asString()).isEqualTo("filled");
        assertThat(buy.get("fills").get(0).get("price").asLong()).isEqualTo(400_000); // resting price wins
        JsonNode book = get(null, "/api/v1/market/TCS/book");
        assertThat(book.get("asks").get(0).get("quantity").asLong()).isEqualTo(6);
        JsonNode trades = get(null, "/api/v1/market/TCS/trades");
        assertThat(trades).hasSize(1);
        assertThat(trades.get(0).get("aggressor").asString()).isEqualTo("BUY");
    }

    @Test
    void accountLabelsGiveOneLoginSeparateAccounts() {
        JsonNode main = get(bot("demo"), "/api/v1/me");
        JsonNode mm = client.mutateWith(bot("demo"))
                .get()
                .uri("/api/v1/me")
                .header("X-Prayog-Account", "mm")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(main.get("accountId").asLong())
                .isNotEqualTo(mm.get("accountId").asLong());
        assertThat(mm.get("accountLabel").asString()).isEqualTo("mm");

        // An account's order is invisible to the same login's other accounts.
        JsonNode placed = place(
                bot("demo"),
                "mm",
                Map.of("symbol", "INFY", "side", "SELL", "type", "LIMIT", "price", 151_000, "quantity", 1));
        client.mutateWith(bot("demo"))
                .delete()
                .uri("/api/v1/orders/" + placed.get("orderId").asLong())
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void accessRules() {
        client.post().uri("/api/v1/orders").exchange().expectStatus().isUnauthorized();
        client.mutateWith(trader("alice"))
                .get()
                .uri("/api/v1/ops/status")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.mutateWith(ops())
                .get()
                .uri("/api/v1/ops/status")
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(ops())
                .get()
                .uri("/api/v1/orders")
                .exchange()
                .expectStatus()
                .isForbidden(); // ops is not a trading role
        client.get().uri("/api/v1/instruments").exchange().expectStatus().isOk(); // public
        client.get().uri("/actuator/health").exchange().expectStatus().isOk();
    }

    @Test
    void badRequestsAreRefusedBeforeTheRing() {
        client.mutateWith(trader("alice"))
                .post()
                .uri("/api/v1/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("symbol", "INFY", "side", "BUY", "type", "LIMIT", "quantity", 1))
                .exchange()
                .expectStatus()
                .isBadRequest();
        client.mutateWith(trader("alice"))
                .get()
                .uri("/api/v1/me")
                .header("X-Prayog-Account", "Not Valid!")
                .exchange()
                .expectStatus()
                .isBadRequest();
        JsonNode rejected = place(
                trader("alice"),
                null,
                Map.of("symbol", "INFY", "side", "BUY", "type", "LIMIT", "price", 149_001, "quantity", 1));
        assertThat(rejected.get("status").asString()).isEqualTo("rejected");
        assertThat(rejected.get("reason").asString()).isEqualTo("PRICE_NOT_ON_TICK");
    }

    @Test
    void aRetryWithTheSameClientOrderIdCreatesNoSecondOrder() {
        Map<String, Object> order = Map.of(
                "symbol",
                "INFY",
                "side",
                "BUY",
                "type",
                "LIMIT",
                "price",
                140_000,
                "quantity",
                1,
                "clientOrderId",
                "retry-1");
        assertThat(place(trader("alice"), null, order).get("status").asString()).isEqualTo("resting");
        JsonNode retry = place(trader("alice"), null, order);
        assertThat(retry.get("status").asString()).isEqualTo("rejected");
        assertThat(retry.get("reason").asString()).isEqualTo("DUPLICATE_CLIENT_ORDER_ID");
        assertThat(get(trader("alice"), "/api/v1/orders")).hasSize(1);
        // Another account may use the same id.
        assertThat(place(trader("bob"), null, order).get("status").asString()).isEqualTo("resting");
    }

    @Test
    void aTraderIsRateLimited() {
        int created = 0;
        int limited = 0;
        for (int i = 0; i < 12; i++) { // test limit: burst 5
            int status = client.mutateWith(trader("flood"))
                    .post()
                    .uri("/api/v1/orders")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(
                            Map.of("symbol", "INFY", "side", "BUY", "type", "LIMIT", "price", 140_000, "quantity", 1))
                    .exchange()
                    .returnResult(String.class)
                    .getStatus()
                    .value();
            if (status == 201) {
                created++;
            } else if (status == 429) {
                limited++;
            }
        }
        assertThat(created).isBetween(5, 7);
        assertThat(limited).isEqualTo(12 - created);
    }

    @Test
    void theMarketDataSocketSendsASnapshotThenNumberedChanges() throws Exception {
        int port = Integer.parseInt(context.getEnvironment().getProperty("local.server.port"));
        List<JsonNode> received = new CopyOnWriteArrayList<>();
        ObjectMapper json = context.getBean(ObjectMapper.class);
        var socket = new ReactorNettyWebSocketClient()
                .execute(
                        URI.create("ws://localhost:" + port + "/api/v1/ws/market?symbols=HDFCBANK"),
                        session -> session.receive()
                                .map(m -> json.readTree(m.getPayloadAsText()))
                                .doOnNext(received::add)
                                .then())
                .subscribe();
        try {
            awaitUntil(() -> !received.isEmpty());
            JsonNode snapshot = received.get(0);
            assertThat(snapshot.get("type").asString()).isEqualTo("snapshot");
            long seq = snapshot.get("seq").asLong();

            place(
                    trader("alice"),
                    null,
                    Map.of("symbol", "HDFCBANK", "side", "BUY", "type", "LIMIT", "price", 164_000, "quantity", 7));
            awaitUntil(() ->
                    received.stream().anyMatch(m -> "book".equals(m.get("type").asString())));
            JsonNode book = received.stream()
                    .filter(m -> "book".equals(m.get("type").asString()))
                    .findFirst()
                    .orElseThrow();
            assertThat(book.get("seq").asLong()).isEqualTo(seq + 1);
            assertThat(book.get("changes").get(0).get("price").asLong()).isEqualTo(164_000);
            assertThat(book.get("changes").get(0).get("quantity").asLong()).isEqualTo(7);
        } finally {
            socket.dispose();
        }
    }

    @Test
    void aRestartedExchangeStillHasTheOpenOrders() {
        long orderId = place(
                        trader("alice"),
                        null,
                        Map.of("symbol", "RELIANCE", "side", "SELL", "type", "LIMIT", "price", 291_000, "quantity", 3))
                .get("orderId")
                .asLong();
        context.close();

        context = startApp(journal);
        client = clientFor(context);
        JsonNode open = get(trader("alice"), "/api/v1/orders");
        assertThat(open).hasSize(1);
        assertThat(open.get(0).get("orderId").asLong()).isEqualTo(orderId);
        assertThat(get(null, "/api/v1/market/RELIANCE/book")
                        .get("asks")
                        .get(0)
                        .get("price")
                        .asLong())
                .isEqualTo(291_000);
        assertThat(get(ops(), "/api/v1/ops/status").get("recoveredFromJournal").asBoolean())
                .isTrue();
    }

    @Test
    void aRestartStartsFromTheShutdownSnapshotWithMarketDataIntact() {
        place(
                trader("alice"),
                null,
                Map.of("symbol", "TCS", "side", "SELL", "type", "LIMIT", "price", 401_000, "quantity", 5));
        place(trader("bob"), null, Map.of("symbol", "TCS", "side", "BUY", "type", "MARKET", "quantity", 2));
        long resting = place(
                        trader("alice"),
                        null,
                        Map.of("symbol", "TCS", "side", "BUY", "type", "LIMIT", "price", 399_000, "quantity", 4))
                .get("orderId")
                .asLong();
        JsonNode tickerBefore = ticker("TCS");
        context.close(); // takes a snapshot on the way down

        context = startApp(journal);
        client = clientFor(context);
        JsonNode status = get(ops(), "/api/v1/ops/status");
        assertThat(status.get("recoveredFromSnapshotInputSeq").asLong())
                .as("recovery started from the shutdown snapshot")
                .isPositive();
        assertThat(ticker("TCS")).isEqualTo(tickerBefore);
        assertThat(get(trader("alice"), "/api/v1/orders").findValuesAsString("orderId"))
                .contains(Long.toString(resting));
        JsonNode book = get(null, "/api/v1/market/TCS/book");
        assertThat(book.get("asks").get(0).get("quantity").asLong()).isEqualTo(3);
        assertThat(get(null, "/api/v1/market/TCS/trades")).hasSize(1);

        // Taken on request too; trading carries on as normal.
        JsonNode taken = client.mutateWith(ops())
                .post()
                .uri("/api/v1/ops/snapshot")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(taken.get("inputSeq").asLong())
                .isGreaterThan(status.get("recoveredFromSnapshotInputSeq").asLong());
        awaitUntil(() ->
                get(ops(), "/api/v1/ops/status").get("lastSnapshotInputSeq").asLong()
                        == taken.get("inputSeq").asLong());
    }

    private JsonNode ticker(String symbol) {
        for (JsonNode t : get(null, "/api/v1/market/tickers")) {
            if (symbol.equals(t.get("symbol").asString())) {
                return t;
            }
        }
        throw new AssertionError("no ticker for " + symbol);
    }

    @Test
    void adminEndpointsAreForAdminsAndTheSimulationFeedForBots() {
        client.mutateWith(trader("alice"))
                .get()
                .uri("/api/v1/admin/overview")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.mutateWith(ops())
                .get()
                .uri("/api/v1/admin/overview")
                .exchange()
                .expectStatus()
                .isForbidden();
        client.mutateWith(admin())
                .get()
                .uri("/api/v1/admin/overview")
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(admin())
                .get()
                .uri("/api/v1/ops/status")
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(bot("agents"))
                .get()
                .uri("/api/v1/simulation")
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(trader("alice"))
                .get()
                .uri("/api/v1/simulation")
                .exchange()
                .expectStatus()
                .isForbidden();
        // The ops page steers the simulation, but sees nothing else of the admin console.
        client.mutateWith(ops())
                .get()
                .uri("/api/v1/simulation")
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(ops())
                .put()
                .uri("/api/v1/admin/simulation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("paused", false))
                .exchange()
                .expectStatus()
                .isOk();
        client.mutateWith(trader("alice"))
                .put()
                .uri("/api/v1/admin/simulation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("paused", true))
                .exchange()
                .expectStatus()
                .isForbidden();
        // An admin also trades like anyone else.
        JsonNode placed = place(
                admin(),
                null,
                Map.of("symbol", "INFY", "side", "BUY", "type", "LIMIT", "price", 140_000, "quantity", 1));
        assertThat(placed.get("status").asString()).isEqualTo("resting");
    }

    @Test
    void theSelfTestPassesOnAHealthyExchange() {
        JsonNode checks = client.mutateWith(admin())
                .post()
                .uri("/api/v1/admin/selftest")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        java.util.Map<String, Boolean> results = new java.util.LinkedHashMap<>();
        checks.forEach(c -> results.put(c.get("name").asString(), c.get("ok").asBoolean()));
        assertThat(results).hasSize(7);
        // No simulated traders run in this test, so "traders are trading" may fail; everything else must pass.
        results.forEach((name, ok) -> {
            if (!name.startsWith("Simulated traders")) {
                assertThat(ok).as(name + ": " + checks).isTrue();
            }
        });
    }

    @Test
    void theAdminSteersTheSimulation() {
        JsonNode state = client.mutateWith(admin())
                .put()
                .uri("/api/v1/admin/simulation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "scenario", "volatile", "paused", true, "jump", Map.of("symbol", "TCS", "percent", -2.5)))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
        assertThat(state.get("scenario").asString()).isEqualTo("volatile");
        assertThat(state.get("paused").asBoolean()).isTrue();
        assertThat(state.get("jumps").get(0).get("percent").asDouble()).isEqualTo(-2.5);
        JsonNode seenByBots = get(bot("agents"), "/api/v1/simulation");
        assertThat(seenByBots.get("version").asLong())
                .isEqualTo(state.get("version").asLong());
        client.mutateWith(admin())
                .put()
                .uri("/api/v1/admin/simulation")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("scenario", "wild"))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void accountsJourneysAndReplayAreVisibleToTheRightPeople() {
        long orderId = place(
                        trader("alice"),
                        null,
                        Map.of("symbol", "INFY", "side", "SELL", "type", "LIMIT", "price", 150_000, "quantity", 5))
                .get("orderId")
                .asLong();
        place(trader("bob"), null, Map.of("symbol", "INFY", "side", "BUY", "type", "MARKET", "quantity", 2));

        JsonNode journey = get(trader("alice"), "/api/v1/orders/" + orderId + "/journey");
        assertThat(journey.get("steps").get(0).get("event").asString()).isEqualTo("accepted");
        assertThat(journey.get("steps").get(1).get("event").asString()).isEqualTo("trade");
        assertThat(journey.get("steps").get(1).get("detail").get("role").asString())
                .isEqualTo("maker");
        client.mutateWith(trader("bob"))
                .get()
                .uri("/api/v1/orders/" + orderId + "/journey")
                .exchange()
                .expectStatus()
                .isNotFound();
        assertThat(get(admin(), "/api/v1/orders/" + orderId + "/journey").get("steps"))
                .hasSize(2);

        JsonNode accounts = get(admin(), "/api/v1/admin/accounts");
        assertThat(accounts.findValuesAsString("username")).contains("alice", "bob");

        JsonNode window = get(admin(), "/api/v1/admin/replay?symbol=INFY&minutes=60");
        assertThat(window.get("symbol").asString()).isEqualTo("INFY");
        assertThat(window.get("frames").size() + window.get("asks").size()).isPositive();
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static ConfigurableApplicationContext startApp(Path journal) {
        return new SpringApplication(ExchangeApplication.class)
                .run(
                        "--server.port=0",
                        "--prayog.exchange.journal-dir=" + journal,
                        "--prayog.exchange.clock.start-date=2026-10-05",
                        "--prayog.exchange.clock.start-time=10:00",
                        "--prayog.exchange.rate-limit.orders-per-second=1",
                        "--prayog.exchange.rate-limit.burst=5",
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/unused");
    }

    private static WebTestClient clientFor(ConfigurableApplicationContext context) {
        return WebTestClient.bindToApplicationContext(context)
                .apply(springSecurity())
                .configureClient()
                .responseTimeout(Duration.ofSeconds(10))
                .build();
    }

    private void awaitOpen() {
        awaitUntil(() -> "OPEN".equals(get(null, "/api/v1/session").get("state").asString()));
    }

    private JsonNode place(JwtMutator who, String label, Map<String, Object> body) {
        var request = client.mutateWith(who).post().uri("/api/v1/orders").contentType(MediaType.APPLICATION_JSON);
        if (label != null) {
            request = request.header("X-Prayog-Account", label);
        }
        return request.bodyValue(body)
                .exchange()
                .expectStatus()
                .isCreated()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private JsonNode get(JwtMutator who, String path) {
        WebTestClient c = who == null ? client : client.mutateWith(who);
        return c.get()
                .uri(path)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(JsonNode.class)
                .returnResult()
                .getResponseBody();
    }

    private static JwtMutator trader(String subject) {
        return token(subject, "prayog-web", "trader");
    }

    private static JwtMutator bot(String subject) {
        return token(subject, "prayog-bot-demo", "bot");
    }

    private static JwtMutator admin() {
        return mockJwt()
                .jwt(j -> j.subject("admin-person")
                        .claim("preferred_username", "admin1")
                        .claim("azp", "prayog-web")
                        .claim("realm_access", Map.of("roles", List.of("trader", "ops", "admin"))))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_trader"),
                        new SimpleGrantedAuthority("ROLE_ops"),
                        new SimpleGrantedAuthority("ROLE_admin"));
    }

    private static JwtMutator ops() {
        return token("ops-person", "prayog-web", "ops");
    }

    private static JwtMutator token(String subject, String clientId, String role) {
        return mockJwt()
                .jwt(j -> j.subject(subject)
                        .claim("preferred_username", subject)
                        .claim("azp", clientId)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            Mono.delay(Duration.ofMillis(50)).block();
        }
    }
}
