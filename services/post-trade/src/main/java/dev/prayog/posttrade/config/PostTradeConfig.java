package dev.prayog.posttrade.config;

import dev.prayog.posttrade.kafka.EventConsumer;
import dev.prayog.posttrade.leaderboard.Leaderboard;
import dev.prayog.posttrade.ledger.Charges;
import dev.prayog.posttrade.ledger.Ledger;
import dev.prayog.posttrade.ledger.LedgerQueries;
import io.micrometer.core.instrument.MeterRegistry;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class PostTradeConfig {

    private static final Logger log = LoggerFactory.getLogger(PostTradeConfig.class);

    @Bean
    Charges charges(PostTradeProperties props) {
        var c = props.charges();
        return new Charges(c.brokeragePpm(), c.brokerageCapPaise(), c.feesPpm());
    }

    @Bean
    Ledger ledger(DSLContext db, Charges charges) {
        return new Ledger(db, charges);
    }

    @Bean
    LedgerQueries ledgerQueries(DSLContext db) {
        return new LedgerQueries(db);
    }

    @Bean
    Leaderboard leaderboard(StringRedisTemplate redis, LedgerQueries queries, DSLContext db) {
        return new Leaderboard(redis, queries, db);
    }

    @Bean
    EventConsumer eventConsumer(Ledger ledger, LedgerQueries queries, Leaderboard board, MeterRegistry meters) {
        return new EventConsumer(ledger, queries, board, meters);
    }

    /**
     * A batch that fails (database down) is retried every second, forever. Spring Kafka's default gives up after a
     * few attempts and skips the batch: for a ledger that would silently lose trades. Waiting is the safe choice.
     */
    @Bean
    CommonErrorHandler kafkaErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                (record, e) -> log.error("unreachable: batches are retried until they succeed", e),
                new FixedBackOff(1_000L, FixedBackOff.UNLIMITED_ATTEMPTS));
        handler.setLogLevel(org.springframework.kafka.KafkaException.Level.WARN);
        return handler;
    }
}
