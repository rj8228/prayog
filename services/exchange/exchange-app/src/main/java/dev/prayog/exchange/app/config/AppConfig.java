package dev.prayog.exchange.app.config;

import dev.prayog.exchange.app.account.AccountDirectory;
import dev.prayog.exchange.app.account.AccountHub;
import dev.prayog.exchange.app.admin.JournalViews;
import dev.prayog.exchange.app.admin.SelfTest;
import dev.prayog.exchange.app.admin.SimulationControl;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.app.security.RateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the exchange: hubs, runtime (journal + pipeline + clock) and the gateway's rate limiter. */
@Configuration
public class AppConfig {

    @Bean
    MarketHub marketHub(ExchangeProperties props) {
        return new MarketHub(
                props.marketData().tradeHistory(), props.marketData().subscriberBuffer());
    }

    @Bean
    AccountHub accountHub(ExchangeProperties props) {
        return new AccountHub(props.marketData().subscriberBuffer());
    }

    @Bean(destroyMethod = "close")
    ExchangeRuntime exchangeRuntime(
            ExchangeProperties props, MarketHub market, AccountHub accounts, MeterRegistry meters) throws IOException {
        ExchangeRuntime runtime = ExchangeRuntime.start(props, market, accounts);
        Gauge.builder("prayog.ring.remaining", runtime, r -> r.status().ringRemaining())
                .description("free slots in the ring; near zero means a stage is falling behind")
                .register(meters);
        Gauge.builder("prayog.input.seq", runtime, r -> r.status().lastProcessedInputSeq())
                .description("last journaled and published input sequence")
                .register(meters);
        Gauge.builder("prayog.marketdata.subscribers", market, MarketHub::subscriberCount)
                .register(meters);
        Gauge.builder("prayog.kafka.lag", runtime, r -> r.status().kafka().lag())
                .description("events in the journal not yet acknowledged by Kafka (ADR 0013)")
                .register(meters);
        Gauge.builder("prayog.kafka.connected", runtime, r -> r.status().kafka().connected() ? 1 : 0)
                .register(meters);
        Gauge.builder(
                        "prayog.kafka.published.seq",
                        runtime,
                        r -> r.status().kafka().publishedSeq())
                .register(meters);
        Gauge.builder("prayog.snapshot.input.seq", runtime, r -> r.status().lastSnapshotInputSeq())
                .description("input seq of the last snapshot written (ADR 0016)")
                .register(meters);
        Gauge.builder("prayog.private.subscribers", accounts, AccountHub::subscriberCount)
                .register(meters);
        return runtime;
    }

    @Bean
    AccountDirectory accountDirectory() {
        return new AccountDirectory();
    }

    @Bean
    SimulationControl simulationControl() {
        return new SimulationControl();
    }

    @Bean
    JournalViews journalViews(ExchangeProperties props) {
        return new JournalViews(props.journalDir());
    }

    @Bean
    SelfTest selfTest(ExchangeRuntime exchange, MarketHub market, ExchangeProperties props) {
        return new SelfTest(exchange, market, props.journalDir());
    }

    @Bean
    RateLimiter rateLimiter() {
        return new RateLimiter(System::nanoTime);
    }
}
