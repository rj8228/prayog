package dev.prayog.exchange.app.api;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Traefik sends account history, P&L and the leaderboard to the post-trade service (ADR 0015). While that service is
 * down or restarting, its route disappears and those requests fall through to the exchange's catch-all route. Answer
 * them plainly: 503 "post-trade unavailable, retry", rather than a confusing 403 or 404.
 */
@RestController
public class PostTradeFallback {

    @RequestMapping({"/api/v1/account/**", "/api/v1/leaderboard", "/api/v1/post-trade/**"})
    ResponseEntity<Map<String, String>> unavailable() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "2")
                .body(Map.of(
                        "error", "post_trade_unavailable",
                        "message", "the post-trade service is not answering; retry in a few seconds"));
    }
}
