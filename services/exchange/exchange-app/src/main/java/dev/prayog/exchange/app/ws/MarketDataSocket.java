package dev.prayog.exchange.app.ws;

import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * Public market data: {@code /api/v1/ws/market?symbols=INFY,TCS&depth=20} (all symbols if none given).
 *
 * <p>Protocol: one {@code snapshot} per symbol, then {@code trade} and {@code book} messages with a per-symbol
 * {@code seq} one higher than the last, plus {@code session} changes and a {@code heartbeat} every 5 s. A client that
 * sees a gap in {@code seq} should reconnect for a fresh snapshot.
 */
@Component
public class MarketDataSocket implements WebSocketHandler {

    private final MarketHub market;
    private final ExchangeRuntime exchange;
    private final ObjectMapper json;

    public MarketDataSocket(MarketHub market, ExchangeRuntime exchange, ObjectMapper json) {
        this.market = market;
        this.exchange = exchange;
        this.json = json;
    }

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        URI uri = session.getHandshakeInfo().getUri();
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        Set<String> symbols = new LinkedHashSet<>();
        String requested = params.getFirst("symbols");
        if (requested == null || requested.isBlank()) {
            symbols.addAll(market.listedSymbols());
        } else {
            symbols.addAll(Arrays.asList(requested.split(",")));
        }
        int depth = parseDepth(params.getFirst("depth"));
        Flux<Object> messages = Flux.merge(
                market.subscribe(symbols, depth),
                Flux.interval(Duration.ofSeconds(5)).map(i -> new Heartbeat("heartbeat", exchange.simTime())));
        return session.send(messages.map(m -> session.textMessage(json.writeValueAsString(m))))
                .and(session.receive().then());
    }

    private static int parseDepth(String value) {
        try {
            return value == null ? 20 : Math.max(1, Math.min(Integer.parseInt(value), 500));
        } catch (NumberFormatException e) {
            return 20;
        }
    }
}
