package dev.prayog.exchange.app.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.BookUpdate;
import dev.prayog.contracts.event.EventJson;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.contracts.event.SessionStateChanged;
import dev.prayog.exchange.core.journal.FileJournal;
import dev.prayog.exchange.core.journal.JournalCodec;
import dev.prayog.exchange.core.journal.JournalHandler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import org.agrona.ExpandableDirectByteBuffer;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The publisher against Kafka's in-memory {@link MockProducer}: we decide which sends succeed and when. */
class KafkaEventPublisherTest {

    private static final String TOPIC = "prayog.exchange.events.v1";
    private static final KafkaEventPublisher.Settings FAST =
            new KafkaEventPublisher.Settings(TOPIC, 7, 1_000, Duration.ofMillis(1), Duration.ofMillis(20));

    @TempDir
    Path dir;

    private final List<MockProducer<String, byte[]>> producers = new CopyOnWriteArrayList<>();
    private final AtomicLong durable = new AtomicLong();
    private boolean autoComplete = true;
    private KafkaEventPublisher publisher;

    @AfterEach
    void stop() throws InterruptedException {
        if (publisher != null) {
            publisher.close();
        }
    }

    @Test
    void publishesEveryDurableEventInOrderKeyedBySymbol() throws IOException {
        writeEvents(1, 50);
        durable.set(50);
        publisher = start();

        awaitUntil(() -> sent().size() == 50);
        List<ProducerRecord<String, byte[]>> records = sent();
        for (int i = 0; i < 50; i++) {
            ExchangeEvent event = EventJson.fromBytes(records.get(i).value());
            assertThat(event.seq()).isEqualTo(i + 1);
            assertThat(records.get(i).topic()).isEqualTo(TOPIC);
            assertThat(records.get(i).key()).isEqualTo(EventJson.key(event));
            assertThat(new String(records.get(i).headers().lastHeader("eventId").value(), StandardCharsets.UTF_8))
                    .isEqualTo(Long.toString(i + 1));
        }
        assertThat(records.get(9).key()).as("session events have the empty key").isEmpty();
        awaitUntil(() -> publisher.status().publishedSeq() == 50);
        assertThat(publisher.status().lag()).isZero();
        assertThat(publisher.status().connected()).isTrue();
    }

    @Test
    void neverPublishesAnEventBeforeTheJournalHasFlushedIt() throws Exception {
        writeEvents(1, 30);
        durable.set(10);
        publisher = start();

        awaitUntil(() -> sent().size() == 10);
        Thread.sleep(50);
        assertThat(sent()).hasSize(10);
        durable.set(30);
        awaitUntil(() -> sent().size() == 30);
    }

    @Test
    void afterAFailedSendItResendsEverythingAboveTheCheckpoint() throws Exception {
        autoComplete = false;
        writeEvents(1, 20);
        durable.set(20);
        publisher = start();

        awaitUntil(() -> producers.size() == 1 && producers.getFirst().history().size() == 20);
        MockProducer<String, byte[]> first = producers.getFirst();
        for (int i = 0; i < 5; i++) {
            first.completeNext(); // 1..5 acked
        }
        first.errorNext(new org.apache.kafka.common.errors.TimeoutException("broker gone")); // 6 failed

        // A new producer resends from 6. Seqs 1-5 are never sent again: the checkpoint covers them.
        awaitUntil(() -> producers.size() == 2 && producers.get(1).history().size() == 15);
        MockProducer<String, byte[]> second = producers.get(1);
        assertThat(EventJson.fromBytes(second.history().getFirst().value()).seq())
                .isEqualTo(6);
        assertThat(publisher.status().publishedSeq()).isEqualTo(5);
        assertThat(publisher.status().errors()).isPositive();
        while (second.completeNext()) {}
        awaitUntil(() -> publisher.status().publishedSeq() == 20);
    }

    @Test
    void theCheckpointNeverPassesAnUnacknowledgedEvent() throws Exception {
        autoComplete = false;
        writeEvents(1, 3);
        durable.set(3);
        publisher = start();
        awaitUntil(() -> producers.size() == 1 && producers.getFirst().history().size() == 3);

        // Planted-bug guard: acking out of order must not move the checkpoint past seq 1.
        MockProducer<String, byte[]> producer = producers.getFirst();
        producer.completeNext(); // 1
        assertThat(publisher.status().publishedSeq()).isEqualTo(1);
        producer.completeNext(); // 2
        producer.completeNext(); // 3
        assertThat(publisher.status().publishedSeq()).isEqualTo(3);
    }

    @Test
    void aRestartedPublisherResumesFromItsCheckpoint() throws Exception {
        writeEvents(1, 20);
        durable.set(20);
        publisher = start();
        awaitUntil(() -> publisher.status().publishedSeq() == 20);
        publisher.close();

        writeEvents(21, 30);
        durable.set(30);
        publisher = start();
        awaitUntil(() -> producers.size() == 2 && producers.get(1).history().size() == 10);
        assertThat(EventJson.fromBytes(producers.get(1).history().getFirst().value())
                        .seq())
                .isEqualTo(21);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    private KafkaEventPublisher start() {
        return new KafkaEventPublisher(
                        dir,
                        durable::get,
                        () -> {
                            MockProducer<String, byte[]> p = new MockProducer<>(
                                    autoComplete, null, new StringSerializer(), new ByteArraySerializer());
                            producers.add(p);
                            return p;
                        },
                        FAST)
                .start();
    }

    private List<ProducerRecord<String, byte[]>> sent() {
        return producers.stream().flatMap(p -> p.history().stream()).toList();
    }

    // Events 1..to; every tenth is session-wide, the rest are book updates on two symbols.
    private void writeEvents(long from, long to) throws IOException {
        JournalCodec codec = new JournalCodec();
        ExpandableDirectByteBuffer buffer = new ExpandableDirectByteBuffer(256);
        try (FileJournal events = FileJournal.open(dir, JournalHandler.EVENTS, 2_000)) {
            for (long seq = from; seq <= to; seq++) {
                ExchangeEvent event = seq % 10 == 0
                        ? new SessionStateChanged(seq, seq, SessionState.OPEN)
                        : new BookUpdate(seq, seq, seq % 2 == 0 ? "INFY" : "TCS", Side.BUY, 150_000, seq, 1);
                int length = codec.encode(event, buffer, 0);
                events.append(seq, buffer, 0, length);
            }
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 10 s");
            }
            Thread.onSpinWait();
        }
    }
}
