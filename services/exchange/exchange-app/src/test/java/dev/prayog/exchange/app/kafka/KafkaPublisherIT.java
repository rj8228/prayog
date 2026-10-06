package dev.prayog.exchange.app.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.EventJson;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.app.ExchangeApplication;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.journal.JournalCodec;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.JournalReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The exchange publishing to a real Kafka broker. The S15 acceptance test: Kafka goes away in the middle of a session,
 * trading carries on, and once Kafka is back every event in the journal is on the topic. Duplicates are allowed
 * (at-least-once); gaps are not.
 */
@Testcontainers
class KafkaPublisherIT {

    private static final String TOPIC = "prayog.exchange.events.v1";

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.3.1");

    @TempDir
    Path journal;

    private ConfigurableApplicationContext context;

    @BeforeAll
    static void createTopic() throws Exception {
        try (Admin admin =
                Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 3, (short) 1))).all().get();
        }
    }

    @AfterEach
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void killingKafkaMidSessionLosesNoEvents() throws Exception {
        context = startApp();
        trade(0, 40);
        awaitPublished(lastJournalSeq());

        // Kafka stops answering (paused: like a hung broker or a cut network). Trading must not notice.
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            long start = System.nanoTime();
            trade(40, 80);
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start))
                    .as("orders were slowed down by the Kafka outage")
                    .isLessThan(5);
            assertThat(runtime().status().kafka().lag()).isPositive();
            // Stay down until sends time out, so the publisher's failure-and-resend path really runs.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (runtime().status().kafka().errors() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertThat(runtime().status().kafka().errors()).isPositive();
            assertThat(runtime().status().kafka().connected()).isFalse();
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }
        awaitPublished(lastJournalSeq());

        // The exchange restarts (a deploy): it resumes from its checkpoint and keeps publishing.
        context.close();
        context = startApp();
        trade(80, 100);
        long last = lastJournalSeq();
        awaitPublished(last);

        Map<Long, Integer> copies = consumeAll();
        assertThat(new TreeSet<>(copies.keySet())).isEqualTo(journalSeqs());
        List<ExchangeEvent> fromJournal = journalEvents();
        // Every copy on the topic is exactly the journal's event, under the right key.
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            Map<Long, ExchangeEvent> bySeq = new HashMap<>();
            fromJournal.forEach(e -> bySeq.put(e.seq(), e));
            for (ConsumerRecord<String, byte[]> record : readEverything(consumer)) {
                ExchangeEvent event = EventJson.fromBytes(record.value());
                assertThat(event).isEqualTo(bySeq.get(event.seq()));
                assertThat(record.key()).isEqualTo(EventJson.key(event));
            }
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private ConfigurableApplicationContext startApp() {
        return new SpringApplication(ExchangeApplication.class)
                .run(
                        "--server.port=0",
                        "--prayog.exchange.journal-dir=" + journal,
                        "--prayog.exchange.clock.start-date=2026-10-05",
                        "--prayog.exchange.clock.start-time=10:00",
                        "--prayog.exchange.kafka.bootstrap=" + KAFKA.getBootstrapServers(),
                        "--spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:1/unused");
    }

    private ExchangeRuntime runtime() {
        return context.getBean(ExchangeRuntime.class);
    }

    // Pairs of crossing orders from two accounts: accepted, trade, book updates.
    private void trade(int from, int to) throws Exception {
        for (int i = from; i < to; i++) {
            long price = 150_000 + (i % 5) * 5;
            runtime()
                    .submit(new NewOrder("s-" + i, 1, "INFY", Side.SELL, OrderType.LIMIT, price, 2))
                    .get(5, TimeUnit.SECONDS);
            runtime()
                    .submit(new NewOrder("b-" + i, 2, "INFY", Side.BUY, OrderType.LIMIT, price, 1))
                    .get(5, TimeUnit.SECONDS);
        }
    }

    private void awaitPublished(long seq) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (runtime().status().kafka().publishedSeq() < seq) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("published " + runtime().status().kafka() + ", journal at " + seq);
            }
            Thread.sleep(50);
        }
    }

    private long lastJournalSeq() throws Exception {
        return journalSeqs().last();
    }

    private TreeSet<Long> journalSeqs() throws Exception {
        TreeSet<Long> seqs = new TreeSet<>();
        JournalReader.read(journal, JournalHandler.EVENTS, (seq, buffer, offset, length) -> seqs.add(seq));
        return seqs;
    }

    private List<ExchangeEvent> journalEvents() throws Exception {
        JournalCodec codec = new JournalCodec();
        List<ExchangeEvent> events = new ArrayList<>();
        JournalReader.read(
                journal,
                JournalHandler.EVENTS,
                (seq, buffer, offset, length) -> events.add(codec.decodeEvent(buffer, offset)));
        return events;
    }

    private Map<Long, Integer> consumeAll() {
        Map<Long, Integer> copies = new HashMap<>();
        try (KafkaConsumer<String, byte[]> consumer = consumer()) {
            for (ConsumerRecord<String, byte[]> record : readEverything(consumer)) {
                copies.merge(EventJson.fromBytes(record.value()).seq(), 1, Integer::sum);
            }
        }
        return copies;
    }

    private static KafkaConsumer<String, byte[]> consumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new KafkaConsumer<>(props, new StringDeserializer(), new ByteArrayDeserializer());
    }

    // Reads every partition from offset 0 to its current end.
    private static List<ConsumerRecord<String, byte[]>> readEverything(KafkaConsumer<String, byte[]> consumer) {
        List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                .map(p -> new TopicPartition(TOPIC, p.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
        List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("could not read the topic to its end");
            }
            consumer.poll(Duration.ofMillis(200)).forEach(records::add);
        }
        return records;
    }
}
