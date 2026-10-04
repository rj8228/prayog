package dev.prayog.exchange.app.config;

import dev.prayog.exchange.core.Instrument;
import dev.prayog.exchange.core.SessionSchedule;
import dev.prayog.exchange.core.pipeline.PipelineConfig;
import dev.prayog.exchange.core.pipeline.WaitStrategyType;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Everything configurable about the exchange, from {@code application.yaml} and environment variables (for example
 * {@code PRAYOG_EXCHANGE_CLOCK_MULTIPLIER}). Defaults live in {@code application.yaml}.
 */
@ConfigurationProperties("prayog.exchange")
public record ExchangeProperties(
        Path journalDir,
        List<InstrumentConfig> instruments,
        Schedule schedule,
        Clock clock,
        Pipeline pipeline,
        RateLimit rateLimit,
        MarketData marketData) {

    /** One listed symbol. Prices in paise. */
    public record InstrumentConfig(
            String symbol, long tickSize, long maxOrderQuantity, long referencePrice, int bandPercent) {
        public Instrument toInstrument() {
            return new Instrument(symbol, tickSize, maxOrderQuantity, referencePrice, bandPercent);
        }
    }

    /** Trading hours; {@code offset} like {@code +05:30}. */
    public record Schedule(LocalTime open, LocalTime close, String offset) {
        public ZoneOffset zoneOffset() {
            return ZoneOffset.of(offset);
        }

        public SessionSchedule toSchedule() {
            return new SessionSchedule(open, close, zoneOffset());
        }
    }

    /**
     * Sim clock. A fresh session starts at {@code startDate} (default: today) at {@code startTime}. With
     * {@code autoNextDay}, once the close has passed for {@code closedPauseSeconds} of wall time, the clock jumps to
     * the next day's open, so a demo market never sits closed overnight.
     */
    public record Clock(
            int multiplier, LocalDate startDate, LocalTime startTime, boolean autoNextDay, int closedPauseSeconds) {}

    public record Pipeline(int ringSize, WaitStrategyType waitStrategy) {
        public PipelineConfig toConfig() {
            return new PipelineConfig(ringSize, waitStrategy);
        }
    }

    /** Token bucket per account (BUILD_PLAN 16.2 #22). Bots and simulated traders get their own, higher limit. */
    public record RateLimit(int ordersPerSecond, int burst, int botOrdersPerSecond, int botBurst) {}

    /**
     * @param tradeHistory recent trades kept per symbol for snapshots and charts
     * @param subscriberBuffer messages a slow WebSocket client may fall behind before it is disconnected
     */
    public record MarketData(int tradeHistory, int subscriberBuffer) {}

    public List<Instrument> toInstruments() {
        return instruments.stream().map(InstrumentConfig::toInstrument).toList();
    }
}
