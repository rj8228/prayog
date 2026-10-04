package dev.prayog.exchange.app.market;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.Side;
import java.util.List;

/**
 * What the public market-data WebSocket sends. Every message has a {@code type}; per-symbol messages carry a
 * {@code seq} that increases by exactly one per message for that symbol, so a client can detect a gap and
 * resubscribe. Prices are integer paise, times are sim time in epoch microseconds.
 */
public final class MarketMessages {

    private MarketMessages() {}

    /** Any message on the market-data feed. */
    public sealed interface MarketMessage permits Snapshot, BookDelta, TradeMessage, SessionMessage {}

    public record Level(long price, long quantity, int orders) {}

    public record Change(Side side, long price, long quantity, int orders) {}

    /** Per-symbol statistics for the current session. {@code last} is 0 before the first trade. */
    public record Ticker(
            String symbol,
            long referencePrice,
            long last,
            long open,
            long high,
            long low,
            long volume,
            long trades,
            long bestBid,
            long bestAsk) {}

    /** The full book at {@code seq}; apply every later message with a higher seq on top of it. */
    public record Snapshot(
            String type,
            String symbol,
            long seq,
            SessionState session,
            List<Level> bids,
            List<Level> asks,
            List<TradeMessage> trades,
            Ticker ticker)
            implements MarketMessage {}

    /** Levels changed by one command. Quantity 0 removes the level. */
    public record BookDelta(String type, String symbol, long seq, List<Change> changes) implements MarketMessage {}

    public record TradeMessage(
            String type, String symbol, long seq, long tradeId, long price, long quantity, Side aggressor, long simTime)
            implements MarketMessage {}

    public record SessionMessage(String type, SessionState state, long simTime) implements MarketMessage {}
}
