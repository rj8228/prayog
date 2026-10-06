package dev.prayog.posttrade;

import static dev.prayog.posttrade.db.Tables.TRADES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.prayog.contracts.AccountIds;
import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.EventJson;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.Trade;
import dev.prayog.posttrade.leaderboard.Leaderboard;
import dev.prayog.posttrade.ledger.Ledger;
import dev.prayog.posttrade.ledger.LedgerQueries;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The whole service against real Kafka, PostgreSQL and Redis: events in on the topic, ledger, leaderboard and API out.
 * Tokens are simulated with {@code jwt()}; signature checks are Spring Security's and run for real in {@code make e2e}.
 */
@Testcontainers
@SpringBootTest(properties = {"spring.flyway.enabled=true", "spring.kafka.consumer.max-poll-records=50"})
@AutoConfigureMockMvc
class PostTradeIT {

    static final String TOPIC = "prayog.exchange.events.v1";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16.15-alpine");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @Container
    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4.11-alpine").withExposedPorts(6379);

    static final long ALICE = AccountIds.accountId("alice", "main");
    static final long BOB = AccountIds.accountId("bob", "main");
    static final long CAROL = AccountIds.accountId("carol", "main");

    @Autowired
    MockMvc mvc;

    @Autowired
    LedgerQueries queries;

    @Autowired
    Ledger ledger;

    @Autowired
    Leaderboard leaderboard;

    @Autowired
    DSLContext db;

    @Autowired
    org.springframework.data.redis.core.StringRedisTemplate redis;

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        }
    }

    @Test
    void eventsBecomePositionsPnlAndALeaderboardAndRedeliveryChangesNothing() throws Exception {
        // Alice buys 10 INFY from Bob at 1,500.00; Carol buys 5 from Alice at 1,520.00; Bob buys 5 TCS from Carol.
        List<ExchangeEvent> session = List.of(
                new OrderAccepted(1, 1, 1, "b1", BOB, "INFY", Side.SELL, OrderType.LIMIT, 150_000, 10),
                new OrderAccepted(2, 2, 2, "a1", ALICE, "INFY", Side.BUY, OrderType.LIMIT, 150_000, 10),
                new Trade(3, 2, 1, "INFY", 150_000, 10, Side.BUY, 2, 1, ALICE, BOB),
                new OrderAccepted(4, 3, 3, "a2", ALICE, "INFY", Side.SELL, OrderType.LIMIT, 152_000, 5),
                new OrderAccepted(5, 4, 4, "c1", CAROL, "INFY", Side.BUY, OrderType.MARKET, 0, 5),
                new Trade(6, 4, 2, "INFY", 152_000, 5, Side.BUY, 4, 3, CAROL, ALICE),
                new OrderAccepted(7, 5, 5, "c2", CAROL, "TCS", Side.SELL, OrderType.LIMIT, 400_000, 5),
                new OrderAccepted(8, 6, 6, "b2", BOB, "TCS", Side.BUY, OrderType.LIMIT, 400_000, 5),
                new Trade(9, 6, 3, "TCS", 400_000, 5, Side.BUY, 6, 5, BOB, CAROL));
        publish(session);
        await(() -> queries.totals().trades() == 3 && leaderboard.size() == 3);

        // Alice: realised 5 x 20.00 = 100.00; 5 left at cost 1,500.00, marked at 1,520.00: +100.00.
        LedgerQueries.Pnl alice = queries.pnl(ALICE);
        assertThat(alice.realisedPnl()).isEqualTo(10_000);
        assertThat(alice.unrealisedPnl()).isEqualTo(10_000);
        mvc.perform(get("/api/v1/account/pnl").with(user("alice", "trader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(ALICE))
                .andExpect(jsonPath("$.realisedPnl").value(10_000))
                .andExpect(jsonPath("$.netPnl").value(alice.netPnl()))
                .andExpect(jsonPath("$.rank").value(1))
                .andExpect(jsonPath("$.positions[0].symbol").value("INFY"))
                .andExpect(jsonPath("$.positions[0].quantity").value(5));
        mvc.perform(get("/api/v1/account/fills").with(user("alice", "trader")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/v1/account/orders").with(user("alice", "trader")))
                .andExpect(jsonPath("$[0].status").value("filled"))
                .andExpect(jsonPath("$[1].status").value("filled"));

        // The board: Alice first and, having looked herself up, named.
        mvc.perform(get("/api/v1/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").value(3))
                .andExpect(jsonPath("$.entries[0].accountId").value(ALICE))
                .andExpect(jsonPath("$.entries[0].name").value("alice/main"))
                .andExpect(jsonPath("$.entries[0].rank").value(1));

        LedgerQueries.Totals totals = queries.totals();
        assertThat(totals.pnlBeforeCharges()).isZero();
        assertThat(totals.netQuantityBySymbol()).containsEntry("INFY", 0L).containsEntry("TCS", 0L);

        // At-least-once delivery: the publisher resends everything after a crash. Nothing may change.
        Map<Long, LedgerQueries.Pnl> before = queries.pnl(null);
        publish(session);
        await(() -> queries.totals().eventsConsumed() == 2L * session.size());
        assertThat(queries.pnl(null)).isEqualTo(before);
        assertThat(queries.totals().trades()).isEqualTo(3);

        // Redis loses the board: the next trade rebuilds all of it from the ledger, not just the two traders.
        redis.delete(Leaderboard.KEY);
        publish(List.of(new Trade(10, 7, 4, "INFY", 152_500, 1, Side.BUY, 7, 8, ALICE, BOB)));
        await(() -> leaderboard.size() == 3);
    }

    @Test
    void aBatchIsOneTransaction() {
        long trades = db.fetchCount(TRADES);
        List<Ledger.Incoming> batch = List.of(
                new Ledger.Incoming(0, new Trade(100, 1, 100, "INFY", 150_000, 1, Side.BUY, 100, 101, ALICE, BOB)),
                // A symbol longer than the column allows: the database refuses this one.
                new Ledger.Incoming(
                        0, new Trade(101, 1, 101, "X".repeat(40), 150_000, 1, Side.BUY, 102, 103, ALICE, BOB)));
        assertThatThrownBy(() -> ledger.apply(batch)).isInstanceOf(RuntimeException.class);
        assertThat(db.fetchCount(TRADES))
                .as("the first trade was rolled back with the second")
                .isEqualTo(trades);
    }

    @Test
    void accessRules() throws Exception {
        mvc.perform(get("/api/v1/account/pnl")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/post-trade/status").with(user("alice", "trader")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/post-trade/status").with(user("ops-person", "ops")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/account/pnl").with(user("ops-person", "ops"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/account/pnl").with(user("alice", "trader")).header(AccountIds.HEADER, "Not Valid!"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private static JwtRequestPostProcessor user(String subject, String role) {
        return jwt().jwt(j -> j.subject(subject)
                        .claim("preferred_username", subject)
                        .claim("realm_access", Map.of("roles", List.of(role))))
                .authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static void publish(List<ExchangeEvent> events) throws Exception {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all");
        try (KafkaProducer<String, byte[]> producer =
                new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer())) {
            List<java.util.concurrent.Future<?>> sends = new ArrayList<>();
            for (ExchangeEvent e : events) {
                sends.add(producer.send(new ProducerRecord<>(TOPIC, EventJson.key(e), EventJson.toBytes(e))));
            }
            for (var s : sends) {
                s.get();
            }
        }
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 60 s");
            }
            Thread.sleep(100);
        }
    }
}
