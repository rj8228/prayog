package dev.prayog.posttrade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** The post-trade service (S16, S17): positions, P&L, charges, order history and the leaderboard. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class PostTradeApplication {

    public static void main(String[] args) {
        SpringApplication.run(PostTradeApplication.class, args);
    }
}
