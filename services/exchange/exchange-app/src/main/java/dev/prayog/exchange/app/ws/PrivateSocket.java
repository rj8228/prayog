package dev.prayog.exchange.app.ws;

import dev.prayog.exchange.app.account.AccountHub;
import dev.prayog.exchange.app.api.Traders;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.security.Trader;
import java.time.Duration;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

/**
 * An account's own order updates and fills: {@code /api/v1/ws/private?account=mm} with a bearer token in the
 * {@code Authorization} header or, for browsers, {@code ?access_token=...}. The first message is {@code hello} with
 * the account id; then {@code order}, {@code fill} and {@code heartbeat} messages.
 */
@Component
public class PrivateSocket implements WebSocketHandler {

    private final AccountHub accounts;
    private final ExchangeRuntime exchange;
    private final ObjectMapper json;

    public PrivateSocket(AccountHub accounts, ExchangeRuntime exchange, ObjectMapper json) {
        this.accounts = accounts;
        this.exchange = exchange;
        this.json = json;
    }

    record Hello(String type, long accountId, String accountLabel) {}

    @Override
    public Mono<Void> handle(WebSocketSession session) {
        String label = UriComponentsBuilder.fromUri(session.getHandshakeInfo().getUri())
                .build()
                .getQueryParams()
                .getFirst("account");
        return session.getHandshakeInfo().getPrincipal().flatMap(principal -> {
            if (!(principal instanceof JwtAuthenticationToken auth)) {
                return session.close(CloseStatus.POLICY_VIOLATION);
            }
            Trader trader = Traders.from(auth, label);
            Flux<Object> messages = Flux.concat(
                    Flux.just(new Hello("hello", trader.accountId(), trader.label())),
                    Flux.merge(
                            accounts.subscribe(trader.accountId()),
                            Flux.interval(Duration.ofSeconds(5))
                                    .map(i -> new Heartbeat("heartbeat", exchange.simTime()))));
            return session.send(messages.map(m -> session.textMessage(json.writeValueAsString(m))))
                    .and(session.receive().then());
        });
    }
}
