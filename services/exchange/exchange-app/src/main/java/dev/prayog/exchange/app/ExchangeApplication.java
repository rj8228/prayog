package dev.prayog.exchange.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The exchange process: gateway, sequencer, matching engine, journal and market-data publisher in one JVM. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ExchangeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ExchangeApplication.class, args);
    }
}
