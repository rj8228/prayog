package dev.prayog.exchange.app.api;

import dev.prayog.contracts.OrderType;
import dev.prayog.exchange.app.account.AccountDirectory;
import dev.prayog.exchange.app.admin.JournalViews;
import dev.prayog.exchange.app.api.ApiTypes.CancelAllResult;
import dev.prayog.exchange.app.api.ApiTypes.Me;
import dev.prayog.exchange.app.api.ApiTypes.ModifyOrder;
import dev.prayog.exchange.app.api.ApiTypes.OpenOrderView;
import dev.prayog.exchange.app.api.ApiTypes.OrderResult;
import dev.prayog.exchange.app.api.ApiTypes.PlaceOrder;
import dev.prayog.exchange.app.config.ExchangeProperties;
import dev.prayog.exchange.app.core.CommandResult;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.app.security.RateLimiter;
import dev.prayog.exchange.app.security.Trader;
import dev.prayog.exchange.core.CancelOrder;
import dev.prayog.exchange.core.NewOrder;
import dev.prayog.exchange.core.marketdata.OrderTracker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Order entry. Every answer is sent only after the command is in the journal (ADR 0006), and describes exactly what
 * the engine did with it.
 */
@RestController
@RequestMapping("/api/v1")
public class OrdersController {

    private static final Logger log = LoggerFactory.getLogger(OrdersController.class);

    private final ExchangeRuntime exchange;
    private final MarketHub market;
    private final RateLimiter limiter;
    private final ExchangeProperties.RateLimit limits;
    private final MeterRegistry meters;
    private final AccountDirectory directory;
    private final JournalViews journal;

    public OrdersController(
            ExchangeRuntime exchange,
            MarketHub market,
            RateLimiter limiter,
            ExchangeProperties props,
            MeterRegistry meters,
            AccountDirectory directory,
            JournalViews journal) {
        this.directory = directory;
        this.journal = journal;
        this.exchange = exchange;
        this.market = market;
        this.limiter = limiter;
        this.limits = props.rateLimit();
        this.meters = meters;
    }

    @GetMapping("/me")
    Me me(JwtAuthenticationToken auth, @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label) {
        Trader t = Traders.from(auth, label);
        return new Me(t.subject(), t.label(), t.accountId(), t.clientId(), t.roles());
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<OrderResult> place(
            JwtAuthenticationToken auth,
            @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label,
            @RequestBody PlaceOrder body) {
        Trader trader = Traders.from(auth, label);
        if (body.symbol() == null || body.side() == null || body.quantity() == null) {
            throw new IllegalArgumentException("symbol, side and quantity are required");
        }
        OrderType type = body.type() == null ? OrderType.LIMIT : body.type();
        if (type == OrderType.LIMIT && body.price() == null) {
            throw new IllegalArgumentException("price is required for a LIMIT order");
        }
        String clientOrderId =
                body.clientOrderId() == null || body.clientOrderId().isBlank()
                        ? UUID.randomUUID().toString()
                        : body.clientOrderId();
        if (clientOrderId.length() > 64) {
            throw new IllegalArgumentException("clientOrderId is limited to 64 characters");
        }
        throttle(trader);
        NewOrder order = new NewOrder(
                clientOrderId,
                trader.accountId(),
                body.symbol(),
                body.side(),
                type,
                type == OrderType.MARKET ? 0 : body.price(),
                body.quantity());
        long started = System.nanoTime();
        return answer(exchange.submit(order))
                .map(r -> Results.ofNewOrder(r, clientOrderId, body.symbol()))
                .doOnNext(result -> record("new", result, trader, started));
    }

    @DeleteMapping("/orders/{orderId}")
    Mono<OrderResult> cancel(
            JwtAuthenticationToken auth,
            @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label,
            @PathVariable long orderId) {
        Trader trader = Traders.from(auth, label);
        throttle(trader);
        String symbol = symbolOf(orderId, trader);
        String clientOrderId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        return submitChange(
                        new CancelOrder(clientOrderId, trader.accountId(), symbol, orderId),
                        orderId,
                        clientOrderId,
                        symbol)
                .doOnNext(result -> record("cancel", result, trader, started));
    }

    @PatchMapping("/orders/{orderId}")
    Mono<OrderResult> modify(
            JwtAuthenticationToken auth,
            @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label,
            @PathVariable long orderId,
            @RequestBody ModifyOrder body) {
        Trader trader = Traders.from(auth, label);
        if (body.price() == null || body.quantity() == null) {
            throw new IllegalArgumentException("price and quantity (new total) are required");
        }
        throttle(trader);
        String symbol = symbolOf(orderId, trader);
        String clientOrderId = body.clientOrderId() == null ? UUID.randomUUID().toString() : body.clientOrderId();
        long started = System.nanoTime();
        return submitChange(
                        new dev.prayog.exchange.core.ModifyOrder(
                                clientOrderId, trader.accountId(), symbol, orderId, body.price(), body.quantity()),
                        orderId,
                        clientOrderId,
                        symbol)
                .doOnNext(result -> record("modify", result, trader, started));
    }

    /** The caller's orders still open on the book, oldest first. */
    @GetMapping("/orders")
    List<OpenOrderView> openOrders(
            JwtAuthenticationToken auth, @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label) {
        return market.openOrders(Traders.from(auth, label).accountId()).stream()
                .map(OrdersController::view)
                .toList();
    }

    /** Cancels every open order of the caller (a bot's own kill switch). Not rate limited. */
    @DeleteMapping("/orders")
    Mono<CancelAllResult> cancelAll(
            JwtAuthenticationToken auth, @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label) {
        Trader trader = Traders.from(auth, label);
        List<OrderTracker.OpenOrder> open = market.openOrders(trader.accountId());
        return Flux.fromIterable(open)
                .concatMap(o -> answer(exchange.submit(
                        new CancelOrder(UUID.randomUUID().toString(), trader.accountId(), o.symbol(), o.orderId()))))
                .filter(r -> r.events().stream().anyMatch(e -> e instanceof dev.prayog.contracts.event.OrderCancelled))
                .count()
                .map(n -> new CancelAllResult(open.size(), n.intValue()));
    }

    private Mono<OrderResult> submitChange(
            dev.prayog.exchange.core.Command command, long orderId, String clientOrderId, String symbol) {
        return answer(exchange.submit(command))
                .map((CommandResult r) -> Results.ofChange(r, orderId, clientOrderId, symbol));
    }

    /**
     * Waits for the journaled result, then continues on a worker thread. Without the hop, building the answer, metrics,
     * logging and the HTTP write would all run on the pipeline's outbound thread that completes the future, delaying
     * market data for everyone.
     */
    private static Mono<CommandResult> answer(CompletableFuture<CommandResult> pending) {
        return Mono.fromFuture(pending).publishOn(Schedulers.parallel());
    }

    // Cancels and modifies need the symbol; an order we don't know (or that isn't the caller's) is a plain 404.
    private String symbolOf(long orderId, Trader trader) {
        OrderTracker.OpenOrder open = market.openOrder(orderId);
        if (open == null || open.accountId() != trader.accountId()) {
            throw new ApiExceptions.NotFound("no open order " + orderId + " for this account");
        }
        return open.symbol();
    }

    /**
     * The life of one of your orders, from the journal: accepted, each fill (as maker or taker), modifies, cancels.
     * Admins may look at any order.
     */
    @GetMapping("/orders/{orderId}/journey")
    Mono<JournalViews.Journey> journey(
            JwtAuthenticationToken auth,
            @RequestHeader(value = Trader.ACCOUNT_HEADER, required = false) String label,
            @PathVariable long orderId) {
        Trader trader = Traders.from(auth, label);
        return Mono.fromCallable(() -> journal.journey(orderId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(j -> {
                    if (j.steps().isEmpty() || (j.accountId() != trader.accountId() && !trader.isAdmin())) {
                        throw new ApiExceptions.NotFound("no order " + orderId + " for this account");
                    }
                    return j;
                });
    }

    private void throttle(Trader trader) {
        directory.seen(trader);
        ExchangeProperties.RateLimit.Tier tier = limits.tierOf(trader.clientId(), trader.isBot());
        if (!limiter.tryAcquire(trader.accountId(), tier.perSecond(), tier.burst())) {
            meters.counter("prayog.orders", "kind", "any", "outcome", "rate_limited")
                    .increment();
            meters.counter("prayog.rate.limited", "tier", tier.name()).increment();
            throw new ApiExceptions.RateLimited();
        }
    }

    private void record(String kind, OrderResult result, Trader trader, long startedNanos) {
        long elapsed = System.nanoTime() - startedNanos;
        Timer.builder("prayog.order.latency")
                .description("request accepted by the gateway until journaled and answered")
                .tag("kind", kind)
                .publishPercentileHistogram()
                .publishPercentiles(0.5, 0.99, 0.999)
                .register(meters)
                .record(elapsed, TimeUnit.NANOSECONDS);
        meters.counter("prayog.orders", "kind", kind, "outcome", result.status())
                .increment();
        if ("rejected".equals(result.status())) {
            directory.rejected(trader);
        }
        if (log.isDebugEnabled() || "rejected".equals(result.status())) {
            log.atLevel("rejected".equals(result.status()) ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.DEBUG)
                    .addKeyValue("inputSeq", result.inputSeq())
                    .addKeyValue("accountId", trader.accountId())
                    .addKeyValue("client", trader.clientId())
                    .addKeyValue("clientOrderId", result.clientOrderId())
                    .addKeyValue("orderId", result.orderId())
                    .addKeyValue("symbol", result.symbol())
                    .addKeyValue("status", result.status())
                    .addKeyValue("reason", result.reason())
                    .log("{} {} {}", kind, result.status(), result.reason() == null ? "" : result.reason());
        }
    }

    private static OpenOrderView view(OrderTracker.OpenOrder o) {
        return new OpenOrderView(
                o.orderId(),
                o.clientOrderId(),
                o.symbol(),
                o.side(),
                o.price(),
                o.quantity(),
                o.leavesQuantity(),
                o.quantity() - o.leavesQuantity());
    }
}
