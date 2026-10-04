package dev.prayog.exchange.app.ws;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;

/** Routes the two WebSocket endpoints. Security for them is in {@code SecurityConfig}. */
@Configuration
public class WebSocketConfig {

    @Bean
    HandlerMapping webSocketMapping(MarketDataSocket market, PrivateSocket accounts) {
        return new SimpleUrlHandlerMapping(Map.of("/api/v1/ws/market", market, "/api/v1/ws/private", accounts), -1);
    }
}
