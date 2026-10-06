package dev.prayog.posttrade.api;

import dev.prayog.contracts.AccountIds;
import dev.prayog.posttrade.leaderboard.Leaderboard;
import dev.prayog.posttrade.ledger.LedgerQueries;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The caller's own account, from the ledger: the official positions and P&L (the browser's figures are estimates,
 * ADR 0012). The account is the token's subject plus the optional {@code X-Prayog-Account} label, exactly as the
 * exchange computes it, so nobody can read another account.
 */
@RestController
@RequestMapping("/api/v1/account")
public class AccountController {

    public record Summary(
            long accountId,
            String label,
            long realisedPnl,
            long unrealisedPnl,
            long charges,
            long netPnl,
            long trades,
            long rank,
            List<LedgerQueries.PositionView> positions) {}

    private final LedgerQueries ledger;
    private final Leaderboard leaderboard;

    public AccountController(LedgerQueries ledger, Leaderboard leaderboard) {
        this.ledger = ledger;
        this.leaderboard = leaderboard;
    }

    @GetMapping("/pnl")
    Summary pnl(JwtAuthenticationToken auth, @RequestHeader(value = AccountIds.HEADER, required = false) String label) {
        String l = AccountIds.label(label);
        long id = account(auth, l);
        LedgerQueries.Pnl p = ledger.pnl(id);
        return new Summary(
                id,
                l,
                p.realisedPnl(),
                p.unrealisedPnl(),
                p.charges(),
                p.netPnl(),
                p.trades(),
                leaderboard.rank(id),
                ledger.positions(id));
    }

    @GetMapping("/fills")
    List<LedgerQueries.FillView> fills(
            JwtAuthenticationToken auth,
            @RequestHeader(value = AccountIds.HEADER, required = false) String label,
            @RequestParam(defaultValue = "100") int limit) {
        return ledger.fills(account(auth, AccountIds.label(label)), clamp(limit));
    }

    @GetMapping("/orders")
    List<LedgerQueries.OrderView> orders(
            JwtAuthenticationToken auth,
            @RequestHeader(value = AccountIds.HEADER, required = false) String label,
            @RequestParam(defaultValue = "100") int limit) {
        return ledger.orders(account(auth, AccountIds.label(label)), clamp(limit));
    }

    @GetMapping("/rejections")
    List<LedgerQueries.RejectionView> rejections(
            JwtAuthenticationToken auth,
            @RequestHeader(value = AccountIds.HEADER, required = false) String label,
            @RequestParam(defaultValue = "100") int limit) {
        return ledger.rejections(account(auth, AccountIds.label(label)), clamp(limit));
    }

    private long account(JwtAuthenticationToken auth, String label) {
        String subject = auth.getToken().getSubject();
        long id = AccountIds.accountId(subject, label);
        String username = auth.getToken().getClaimAsString("preferred_username");
        leaderboard.name(id, username == null ? subject : username, label);
        return id;
    }

    private static int clamp(int limit) {
        return Math.max(1, Math.min(limit, 1_000));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    java.util.Map<String, String> badRequest(IllegalArgumentException e) {
        return java.util.Map.of("error", "bad_request", "message", e.getMessage());
    }
}
