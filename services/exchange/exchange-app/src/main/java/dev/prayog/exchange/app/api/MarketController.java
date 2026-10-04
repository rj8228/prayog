package dev.prayog.exchange.app.api;

import dev.prayog.exchange.app.api.ApiTypes.InstrumentView;
import dev.prayog.exchange.app.api.ApiTypes.SessionView;
import dev.prayog.exchange.app.config.ExchangeProperties;
import dev.prayog.exchange.app.core.ExchangeRuntime;
import dev.prayog.exchange.app.market.MarketHub;
import dev.prayog.exchange.app.market.MarketMessages.Snapshot;
import dev.prayog.exchange.app.market.MarketMessages.Ticker;
import dev.prayog.exchange.app.market.MarketMessages.TradeMessage;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public market information: no login needed. Live updates are on the {@code /api/v1/ws/market} WebSocket. */
@RestController
@RequestMapping("/api/v1")
public class MarketController {

    private final MarketHub market;
    private final ExchangeRuntime exchange;
    private final ExchangeProperties.Schedule schedule;

    public MarketController(MarketHub market, ExchangeRuntime exchange, ExchangeProperties props) {
        this.market = market;
        this.exchange = exchange;
        this.schedule = props.schedule();
    }

    @GetMapping("/instruments")
    List<InstrumentView> instruments() {
        return market.instruments().stream()
                .map(i -> new InstrumentView(
                        i.symbol(),
                        i.tickSize(),
                        i.maxOrderQuantity(),
                        i.referencePrice(),
                        i.bandPercent(),
                        i.bandLow(),
                        i.bandHigh()))
                .toList();
    }

    @GetMapping("/session")
    SessionView session() {
        return new SessionView(
                market.session(),
                exchange.simTime(),
                exchange.clockMultiplier(),
                schedule.open(),
                schedule.close(),
                schedule.offset());
    }

    @GetMapping("/market/tickers")
    List<Ticker> tickers() {
        return market.tickers();
    }

    @GetMapping("/market/{symbol}/book")
    Snapshot book(@PathVariable String symbol, @RequestParam(defaultValue = "20") int depth) {
        Snapshot snapshot = market.snapshot(symbol, Math.max(1, Math.min(depth, 500)));
        if (snapshot == null) {
            throw new ApiExceptions.NotFound("unknown symbol " + symbol);
        }
        return snapshot;
    }

    @GetMapping("/market/{symbol}/trades")
    List<TradeMessage> trades(@PathVariable String symbol, @RequestParam(defaultValue = "200") int limit) {
        if (market.ticker(symbol) == null) {
            throw new ApiExceptions.NotFound("unknown symbol " + symbol);
        }
        return market.trades(symbol, Math.max(1, Math.min(limit, 5_000)));
    }
}
