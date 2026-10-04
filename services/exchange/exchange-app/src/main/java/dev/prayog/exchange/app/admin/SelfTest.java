package dev.prayog.exchange.app.admin;

import dev.prayog.contracts.OrderType;
import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import dev.prayog.contracts.event.OrderAccepted;
import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.contracts.event.OrderRejected;
import dev.prayog.exchange.app.core.CommandResult;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.app.market.MarketMessages.Ticker;
import dev.prayog.exchange.app.security.Trader;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.journal.Replay;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The admin's "run checks" button: a handful of live checks that need no restart and no outside tools. Each returns
 * a result rather than throwing, so one failure never hides the others.
 */
public final class SelfTest {

    public record Check(String name, boolean ok, String detail, long millis) {}

    /** A separate account for the round-trip check, so it never touches anyone's orders. */
    static final long SELFTEST_ACCOUNT = Trader.accountId("prayog-selftest", "selftest");

    private final ExchangeRuntime exchange;
    private final MarketHub market;
    private final Path journalDir;

    public SelfTest(ExchangeRuntime exchange, MarketHub market, Path journalDir) {
        this.exchange = exchange;
        this.market = market;
        this.journalDir = journalDir;
    }

    public List<Check> run() {
        List<Check> checks = new ArrayList<>();
        checks.add(timed("Order round trip (place, then cancel)", this::roundTrip));
        checks.add(timed("Books are not crossed (best bid < best ask)", this::booksNotCrossed));
        checks.add(timed("Ring has room (back-pressure not engaged)", this::ringHasRoom));
        checks.add(timed("No market-data publishing errors", this::noPublishErrors));
        checks.add(timed("Simulated traders are trading", this::tradesHappening));
        checks.add(timed("Journal replays to the same events (online)", this::onlineReplay));
        return checks;
    }

    private interface Body {
        String run() throws Exception;
    }

    private static Check timed(String name, Body body) {
        long start = System.nanoTime();
        try {
            String detail = body.run();
            return new Check(name, true, detail, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        } catch (Exception | AssertionError e) {
            return new Check(name, false, e.getMessage(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private String roundTrip() throws Exception {
        Instrument instrument = market.instruments().getFirst();
        String clientOrderId = "selftest-" + UUID.randomUUID();
        CommandResult placed = exchange.submit(new NewOrder(
                        clientOrderId,
                        SELFTEST_ACCOUNT,
                        instrument.symbol(),
                        Side.BUY,
                        OrderType.LIMIT,
                        instrument.bandLow(),
                        1))
                .get(5, TimeUnit.SECONDS);
        if (market.session() != SessionState.OPEN) {
            boolean rejected = placed.events().stream().anyMatch(e -> e instanceof OrderRejected);
            check(rejected, "session " + market.session() + " but the order was not rejected");
            return "session " + market.session() + ": correctly rejected (journaled at input seq " + placed.inputSeq()
                    + ")";
        }
        long orderId = placed.events().stream()
                .filter(e -> e instanceof OrderAccepted)
                .map(e -> ((OrderAccepted) e).orderId())
                .findFirst()
                .orElseThrow(() -> new AssertionError("order not accepted: " + placed.events()));
        CommandResult cancelled = exchange.submit(
                        new CancelOrder("selftest-cancel", SELFTEST_ACCOUNT, instrument.symbol(), orderId))
                .get(5, TimeUnit.SECONDS);
        check(cancelled.events().stream().anyMatch(e -> e instanceof OrderCancelled), "cancel did not cancel");
        return "order " + orderId + " accepted at input seq " + placed.inputSeq() + ", cancelled at "
                + cancelled.inputSeq();
    }

    private String booksNotCrossed() {
        List<String> crossed = new ArrayList<>();
        for (Ticker t : market.tickers()) {
            if (t.bestBid() > 0 && t.bestAsk() > 0 && t.bestBid() >= t.bestAsk()) {
                crossed.add(t.symbol());
            }
        }
        check(crossed.isEmpty(), "crossed: " + crossed);
        return market.tickers().size() + " symbols checked";
    }

    private String ringHasRoom() {
        ExchangeRuntime.Status s = exchange.status();
        check(s.ringRemaining() > s.ringCapacity() / 2, s.ringRemaining() + " of " + s.ringCapacity() + " free");
        return s.ringRemaining() + " of " + s.ringCapacity() + " slots free";
    }

    private String noPublishErrors() {
        long errors = exchange.status().publishErrors();
        check(errors == 0, errors + " errors since start");
        return "0 since start";
    }

    private String tradesHappening() throws InterruptedException {
        long before = totalTrades();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (totalTrades() == before && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }
        long after = totalTrades();
        check(after > before || market.session() != SessionState.OPEN, "no trades in 5 s while the market is open");
        return (after - before) + " trades in the last few seconds";
    }

    private long totalTrades() {
        return market.tickers().stream().mapToLong(Ticker::trades).sum();
    }

    private String onlineReplay() throws Exception {
        Replay.OnlineReport report = Replay.checkOnline(journalDir);
        check(report.matches(), "replayed events differ from the event log");
        return report.commands() + " commands replayed; events 1.." + report.comparedUpTo() + " identical (sha256 "
                + report.recorded().sha256().substring(0, 12) + "...)";
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
