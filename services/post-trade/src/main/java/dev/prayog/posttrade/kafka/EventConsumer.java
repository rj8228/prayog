package dev.prayog.posttrade.kafka;

import dev.prayog.contracts.event.EventJson;
import dev.prayog.posttrade.leaderboard.Leaderboard;
import dev.prayog.posttrade.ledger.Ledger;
import dev.prayog.posttrade.ledger.LedgerQueries;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.TreeSet;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Reads the exchange's event stream (ADR 0013) and feeds the ledger, one Kafka batch per database transaction. Kafka
 * offsets are committed only after the transaction (ack mode BATCH), so a crash in between redelivers the batch and
 * the ledger skips what it already has (ADR 0015).
 *
 * <p>The leaderboard is updated after the ledger commits. If Redis is down, the ledger is still correct; the board is
 * marked stale and rebuilt from the ledger on the next batch. Updates and rebuilds both run on the listener thread, so
 * a rebuild can never overwrite a newer update.
 */
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    private final Ledger ledger;
    private final LedgerQueries queries;
    private final Leaderboard leaderboard;
    private final Counter applied;
    private final Counter duplicates;
    private volatile boolean boardStale = true; // rebuilt once at start

    public EventConsumer(Ledger ledger, LedgerQueries queries, Leaderboard leaderboard, MeterRegistry meters) {
        this.ledger = ledger;
        this.queries = queries;
        this.leaderboard = leaderboard;
        this.applied = Counter.builder("prayog.posttrade.events.applied").register(meters);
        this.duplicates = Counter.builder("prayog.posttrade.events.duplicate")
                .description("redelivered events skipped (at-least-once delivery)")
                .register(meters);
    }

    @KafkaListener(topics = "${prayog.post-trade.topic}", batch = "true")
    public void onBatch(List<ConsumerRecord<String, byte[]>> records) {
        List<Ledger.Incoming> batch = records.stream()
                .map(r -> new Ledger.Incoming(r.partition(), EventJson.fromBytes(r.value())))
                .toList();
        Ledger.Changes changes = ledger.apply(batch);
        applied.increment(changes.applied());
        duplicates.increment(changes.duplicates());
        try {
            // Stale after an error, or empty although trades exist (Redis lost its data): rebuild from the ledger.
            if (boardStale || (!changes.accounts().isEmpty() && leaderboard.size() == 0)) {
                rebuildLeaderboard();
                return;
            }
            TreeSet<Long> accounts = new TreeSet<>(changes.accounts());
            accounts.addAll(queries.holders(changes.markedSymbols()));
            leaderboard.update(accounts);
        } catch (RuntimeException e) {
            boardStale = true;
            log.warn("leaderboard update failed; it will be rebuilt from the ledger", e);
        }
    }

    /** Called at start-up and whenever the board may have missed updates. */
    public void rebuildLeaderboard() {
        leaderboard.rebuild();
        boardStale = false;
        log.info("leaderboard rebuilt from the ledger: {} accounts", leaderboard.size());
    }
}
