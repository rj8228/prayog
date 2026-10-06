package dev.prayog.posttrade.api;

import dev.prayog.posttrade.leaderboard.Leaderboard;
import dev.prayog.posttrade.ledger.LedgerQueries;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LeaderboardController {

    public record Board(long accounts, List<Leaderboard.Entry> entries) {}

    private final Leaderboard leaderboard;
    private final LedgerQueries ledger;

    public LeaderboardController(Leaderboard leaderboard, LedgerQueries ledger) {
        this.leaderboard = leaderboard;
        this.ledger = ledger;
    }

    /** Top accounts by net P&L (public, like a real trading competition's board). */
    @GetMapping("/api/v1/leaderboard")
    Board leaderboard(@RequestParam(defaultValue = "20") int limit) {
        return new Board(leaderboard.size(), leaderboard.top(Math.max(1, Math.min(limit, 100))));
    }

    /** Ledger totals and consumer progress, for ops and the end-to-end checks. */
    @GetMapping("/api/v1/post-trade/status")
    LedgerQueries.Totals status() {
        return ledger.totals();
    }
}
