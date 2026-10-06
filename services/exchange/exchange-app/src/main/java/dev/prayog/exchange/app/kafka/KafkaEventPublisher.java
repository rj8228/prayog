package dev.prayog.exchange.app.kafka;

import dev.prayog.contracts.event.EventJson;
import dev.prayog.contracts.event.ExchangeEvent;
import dev.prayog.exchange.core.journal.JournalCodec;
import dev.prayog.exchange.core.journal.JournalHandler;
import dev.prayog.exchange.core.journal.JournalTailer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes every exchange event to Kafka (topic {@code prayog.exchange.events.v1}, BUILD_PLAN 16.2 #18-19).
 *
 * <p><b>Off the order path.</b> This is not a pipeline stage. It runs on its own thread and follows the event log on
 * disk ({@link JournalTailer}), reading only events the journal stage has already flushed ({@code durableSeq}). If
 * Kafka is slow or down, this thread falls behind; the ring, the matching thread and clients never notice. When Kafka
 * comes back it simply carries on from where it got to: catching up after downtime and normal running are the same
 * loop.
 *
 * <p><b>Offsets vs event ids.</b> Kafka gives each record an <i>offset</i>, its position in one partition. Our own
 * <i>event id</i> is the exchange's event seq, the same on every partition and in the journal. Consumers deduplicate
 * on the event id, never the offset, because a resent event gets a new offset.
 *
 * <p><b>At least once.</b> The checkpoint ({@link PublishCheckpoint}) moves only past events Kafka has acknowledged.
 * After a crash or a failed send, everything above it is sent again, so some events can reach Kafka twice but none
 * are lost. The producer's own idempotence stops duplicates caused by its internal retries, and keeps each
 * partition's order.
 *
 * <p><b>Order.</b> Records are keyed by symbol ({@link EventJson#key}), so one symbol's events land on one partition in
 * seq order. There is no order across partitions; consumers that need it can sort by event id.
 */
public final class KafkaEventPublisher implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    /** File next to the journal holding the publisher's checkpoint. */
    public static final String CHECKPOINT_FILE = "kafka-publisher.checkpoint";

    /** What the ops status reports about the publisher. */
    public record Status(boolean enabled, boolean connected, long publishedSeq, long lag, long errors) {}

    /**
     * @param maxInFlight sent but unacknowledged events allowed before the publisher waits (bounds memory when Kafka
     *     is slow)
     */
    public record Settings(String topic, int batch, int maxInFlight, Duration idleWait, Duration retryWait) {
        public static Settings defaults(String topic) {
            return new Settings(topic, 1_000, 20_000, Duration.ofMillis(5), Duration.ofSeconds(1));
        }
    }

    private final Path journalDir;
    private final LongSupplier durableSeq;
    private final Supplier<Producer<String, byte[]>> producers;
    private final Settings settings;
    private final PublishCheckpoint checkpoint;
    private final JournalCodec codec = new JournalCodec();
    private final AtomicBoolean failed = new AtomicBoolean();
    private final AtomicLong errors = new AtomicLong();
    private final Thread thread;

    private volatile boolean running = true;
    private volatile boolean connected;
    private Producer<String, byte[]> producer;
    private JournalTailer tailer;

    public KafkaEventPublisher(
            Path journalDir, LongSupplier durableSeq, Supplier<Producer<String, byte[]>> producers, Settings settings) {
        this.journalDir = journalDir;
        this.durableSeq = durableSeq;
        this.producers = producers;
        this.settings = settings;
        this.checkpoint = PublishCheckpoint.load(journalDir.resolve(CHECKPOINT_FILE));
        this.thread = new Thread(this::run, "prayog-kafka-publisher");
        this.thread.setDaemon(true);
    }

    /** A real Kafka producer for {@code bootstrap}, configured for at-least-once with per-partition order. */
    public static Supplier<Producer<String, byte[]>> producers(String bootstrap) {
        Map<String, Object> config = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrap,
                ProducerConfig.CLIENT_ID_CONFIG,
                "prayog-exchange",
                // Wait until the broker has stored the record before calling it sent.
                ProducerConfig.ACKS_CONFIG,
                "all",
                // The broker drops duplicates caused by the producer's own retries, and order per partition holds.
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.LINGER_MS_CONFIG,
                5,
                ProducerConfig.COMPRESSION_TYPE_CONFIG,
                "lz4",
                // Fail fast when Kafka is down, so this thread notices and backs off instead of hanging.
                ProducerConfig.MAX_BLOCK_MS_CONFIG,
                2_000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG,
                3_000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG,
                8_000);
        return () -> new KafkaProducer<>(config, new StringSerializer(), new ByteArraySerializer());
    }

    public KafkaEventPublisher start() {
        thread.start();
        return this;
    }

    public Status status() {
        long line = checkpoint.line();
        return new Status(true, connected, line, Math.max(0, durableSeq.getAsLong() - line), errors.get());
    }

    private void run() {
        long lastSave = System.nanoTime();
        while (running) {
            try {
                if (producer == null) {
                    producer = producers.get();
                    tailer = new JournalTailer(journalDir, JournalHandler.EVENTS, checkpoint.line() + 1);
                }
                int sent = 0;
                if (checkpoint.inFlight() < settings.maxInFlight()) {
                    sent = tailer.poll(durableSeq.getAsLong(), settings.batch(), this::send);
                }
                if (failed.get()) {
                    recover(null);
                    continue;
                }
                if (System.nanoTime() - lastSave > 1_000_000_000L) {
                    checkpoint.save();
                    lastSave = System.nanoTime();
                }
                if (sent == 0) {
                    Thread.sleep(settings.idleWait());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (IOException | RuntimeException e) {
                recover(e);
            }
        }
    }

    private void send(long seq, org.agrona.DirectBuffer buffer, int offset, int length) {
        if (failed.get()) {
            return; // the batch is being abandoned; it is resent after recovery
        }
        ExchangeEvent event = codec.decodeEvent(buffer, offset);
        ProducerRecord<String, byte[]> record =
                new ProducerRecord<>(settings.topic(), EventJson.key(event), EventJson.toBytes(event));
        record.headers().add("eventId", Long.toString(seq).getBytes(StandardCharsets.UTF_8));
        checkpoint.sent(seq);
        producer.send(record, (metadata, error) -> {
            if (error != null) {
                failed.set(true);
                errors.incrementAndGet();
            } else {
                connected = true;
                checkpoint.acked(seq);
            }
        });
    }

    // A send failed (Kafka down, timed out): drop the producer and everything in flight, wait, and resend from the
    // checkpoint. Events above the checkpoint may already be in Kafka; consumers skip them by event id.
    private void recover(Exception cause) {
        connected = false;
        if (cause != null) {
            errors.incrementAndGet();
        }
        log.warn(
                "Kafka publishing failed; resending from event {} in {}",
                checkpoint.line() + 1,
                settings.retryWait(),
                cause);
        closeProducer(Duration.ZERO);
        checkpoint.rewind();
        failed.set(false);
        try {
            Thread.sleep(settings.retryWait());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            running = false;
        }
    }

    private void closeProducer(Duration timeout) {
        if (producer != null) {
            try {
                producer.close(timeout);
            } catch (RuntimeException e) {
                log.debug("closing the Kafka producer", e);
            }
            producer = null;
        }
        if (tailer != null) {
            try {
                tailer.close();
            } catch (IOException e) {
                log.debug("closing the journal tailer", e);
            }
            tailer = null;
        }
    }

    /** Stops after sending what is in flight (waiting up to a few seconds) and saves the checkpoint. */
    @Override
    public void close() throws InterruptedException {
        running = false;
        thread.join(10_000);
        closeProducer(Duration.ofSeconds(3)); // close() flushes: in-flight sends get their acks first
        try {
            checkpoint.save();
        } catch (IOException e) {
            log.warn("could not save the Kafka checkpoint; events since the last save will be resent", e);
        }
        log.info("Kafka publisher stopped at event {}", checkpoint.line());
    }
}
