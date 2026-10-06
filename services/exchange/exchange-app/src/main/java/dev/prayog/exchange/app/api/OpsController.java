package dev.prayog.exchange.app.api;

import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.exchange.app.api.ApiTypes.SetAccount;
import dev.prayog.exchange.app.api.ApiTypes.SetClock;
import dev.prayog.exchange.app.api.ApiTypes.SetSession;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.core.SetAccountEnabled;
import dev.prayog.exchange.core.SetSessionState;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Market operations, role {@code ops} only. Every action goes through the ring and the journal like any order. */
@RestController
@RequestMapping("/api/v1/ops")
public class OpsController {

    private static final Logger log = LoggerFactory.getLogger(OpsController.class);

    private final ExchangeRuntime exchange;

    public OpsController(ExchangeRuntime exchange) {
        this.exchange = exchange;
    }

    @GetMapping("/status")
    ExchangeRuntime.Status status() {
        return exchange.status();
    }

    /** Global kill switch: HALTED (cancels only), OPEN, or CLOSED (expires every open order). */
    @PostMapping("/session")
    Mono<Map<String, Object>> session(JwtAuthenticationToken auth, @RequestBody SetSession body) {
        if (body.state() == null) {
            throw new IllegalArgumentException("state is required: OPEN, HALTED or CLOSED");
        }
        log.info("ops {} sets session {}", auth.getToken().getSubject(), body.state());
        return Mono.fromFuture(exchange.submit(new SetSessionState(body.state())))
                .publishOn(Schedulers.parallel())
                .map(r -> Map.of("inputSeq", r.inputSeq(), "events", r.events().size()));
    }

    /** Per-account kill switch: disabling cancels the account's open orders and refuses new ones. */
    @PostMapping("/accounts/{accountId}")
    Mono<Map<String, Object>> account(
            JwtAuthenticationToken auth, @PathVariable long accountId, @RequestBody SetAccount body) {
        if (body.enabled() == null) {
            throw new IllegalArgumentException("enabled is required");
        }
        log.info("ops {} sets account {} enabled={}", auth.getToken().getSubject(), accountId, body.enabled());
        return Mono.fromFuture(exchange.submit(new SetAccountEnabled(accountId, body.enabled())))
                .publishOn(Schedulers.parallel())
                .map(r -> Map.of(
                        "inputSeq",
                        r.inputSeq(),
                        "cancelledOrders",
                        r.events().stream()
                                .filter(e -> e instanceof OrderCancelled)
                                .count()));
    }

    /** Sim speed: 1 = real time, up to 10,000. Ticks still come every 100 ms; each covers more sim time. */
    @PutMapping("/clock")
    Map<String, Object> clock(@RequestBody SetClock body) {
        if (body.multiplier() == null) {
            throw new IllegalArgumentException("multiplier is required");
        }
        exchange.setClockMultiplier(body.multiplier());
        return Map.of("multiplier", exchange.clockMultiplier(), "simTime", exchange.simTime());
    }

    /** Takes a snapshot now (ADR 0016); it is written in the background. */
    @PostMapping("/snapshot")
    Mono<Map<String, Object>> snapshot() {
        return Mono.fromFuture(exchange.takeSnapshot())
                .publishOn(Schedulers.parallel())
                .map(r -> Map.of("inputSeq", r.inputSeq()));
    }

    /** Jumps to the next scheduled open: today's orders expire at the close, then the new day opens. */
    @PostMapping("/clock/next-open")
    Map<String, Object> nextOpen() {
        exchange.skipToNextOpen();
        return Map.of("simTime", exchange.simTime());
    }
}
