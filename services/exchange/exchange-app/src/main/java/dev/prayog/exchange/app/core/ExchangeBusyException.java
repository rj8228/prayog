package dev.prayog.exchange.app.core;

/** The ring is full: the exchange is behind and refuses new work rather than queueing without limit (ADR 0006). */
public final class ExchangeBusyException extends RuntimeException {
    public ExchangeBusyException() {
        super("exchange busy, retry shortly");
    }
}
