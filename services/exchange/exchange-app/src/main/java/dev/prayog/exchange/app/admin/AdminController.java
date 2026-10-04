package dev.prayog.exchange.app.admin;

import dev.prayog.contracts.event.OrderCancelled;
import dev.prayog.exchange.app.account.AccountDirectory;
import dev.prayog.exchange.app.api.ApiExceptions;
import dev.prayog.exchange.app.config.ExchangeProperties;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** The admin console's API (role {@code admin}). Market and clock controls stay under {@code /api/v1/ops}. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final ExchangeRuntime exchange;
    private final MarketHub market;
    private final AccountDirectory directory;
    private final SimulationControl simulation;
    private final SelfTest selfTest;
    private final JournalViews journal;
    private final MeterRegistry meters;
    private final Path journalDir;

    public AdminController(
            ExchangeRuntime exchange,
            MarketHub market,
            AccountDirectory directory,
            SimulationControl simulation,
            SelfTest selfTest,
            JournalViews journal,
            MeterRegistry meters,
            ExchangeProperties props) {
        this.exchange = exchange;
        this.market = market;
        this.directory = directory;
        this.simulation = simulation;
        this.selfTest = selfTest;
        this.journal = journal;
        this.meters = meters;
        this.journalDir = props.journalDir();
    }

    public record Overview(
            ExchangeRuntime.Status status,
            Map<String, Double> orders,
            Map<String, Double> latencyMillis,
            long journalBytes,
            int marketDataSubscribers,
            int accounts,
            SimulationControl.State simulation) {}

    @GetMapping("/overview")
    Overview overview() throws IOException {
        Map<String, Double> orders = new LinkedHashMap<>();
        for (Counter c : meters.find("prayog.orders").counters()) {
            orders.merge(c.getId().getTag("kind") + "/" + c.getId().getTag("outcome"), c.count(), Double::sum);
        }
        Map<String, Double> latency = new LinkedHashMap<>();
        for (Timer t : meters.find("prayog.order.latency").tag("kind", "new").timers()) {
            for (ValueAtPercentile p : t.takeSnapshot().percentileValues()) {
                latency.put("p" + (p.percentile() * 100), p.value(TimeUnit.MILLISECONDS));
            }
            latency.put("mean", t.mean(TimeUnit.MILLISECONDS));
            latency.put("max", t.max(TimeUnit.MILLISECONDS));
        }
        return new Overview(
                exchange.status(),
                orders,
                latency,
                journalBytes(),
                market.subscriberCount(),
                directory.entries().size(),
                simulation.state());
    }

    public record AccountView(AccountDirectory.Entry account, int openOrders) {}

    @GetMapping("/accounts")
    List<AccountView> accounts() {
        return directory.entries().stream()
                .map(e -> new AccountView(e, market.openOrders(e.accountId()).size()))
                .toList();
    }

    /** Cancels every open order of an account (without disabling it; the kill switch is /ops/accounts/{id}). */
    @PostMapping("/accounts/{accountId}/cancel-all")
    Mono<Map<String, Long>> cancelAll(@PathVariable long accountId) {
        List<OrderTracker.OpenOrder> open = market.openOrders(accountId);
        return Flux.fromIterable(open)
                .concatMap(o -> Mono.fromFuture(exchange.submit(
                                new CancelOrder("admin-" + UUID.randomUUID(), accountId, o.symbol(), o.orderId())))
                        .publishOn(Schedulers.parallel()))
                .filter(r -> r.events().stream().anyMatch(e -> e instanceof OrderCancelled))
                .count()
                .map(n -> Map.of("open", (long) open.size(), "cancelled", n));
    }

    @PostMapping("/selftest")
    Mono<List<SelfTest.Check>> selfTest() {
        return Mono.fromCallable(selfTest::run).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/simulation")
    SimulationControl.State simulation() {
        return simulation.state();
    }

    public record SimulationChange(String scenario, Boolean paused, JumpRequest jump) {}

    public record JumpRequest(String symbol, Double percent) {}

    @PutMapping("/simulation")
    SimulationControl.State changeSimulation(@RequestBody SimulationChange body) {
        SimulationControl.State state = simulation.state();
        if (body.scenario() != null) {
            state = simulation.setScenario(body.scenario());
        }
        if (body.paused() != null) {
            state = simulation.setPaused(body.paused());
        }
        if (body.jump() != null) {
            if (body.jump().percent() == null) {
                throw new IllegalArgumentException("jump.percent is required");
            }
            if (body.jump().symbol() != null && market.instrument(body.jump().symbol()) == null) {
                throw new ApiExceptions.NotFound("unknown symbol " + body.jump().symbol());
            }
            state = simulation.jump(body.jump().symbol(), body.jump().percent());
        }
        return state;
    }

    /** The book and trades of one symbol over the last {@code minutes} of sim time, rebuilt from the journal. */
    @GetMapping("/replay")
    Mono<JournalViews.Window> replay(
            @RequestParam String symbol,
            @RequestParam(defaultValue = "10") int minutes,
            @RequestParam(defaultValue = "5000") int maxFrames) {
        if (market.instrument(symbol) == null) {
            throw new ApiExceptions.NotFound("unknown symbol " + symbol);
        }
        long to = exchange.simTime();
        long from = to - Math.max(1, Math.min(minutes, 24 * 60)) * 60_000_000L;
        return Mono.fromCallable(() -> journal.window(symbol, from, to, Math.max(1, Math.min(maxFrames, 20_000))))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private long journalBytes() throws IOException {
        if (!Files.isDirectory(journalDir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(journalDir)) {
            return files.mapToLong(p -> p.toFile().length()).sum();
        }
    }
}
