package dev.prayog.contracts.event;

/**
 * Something the matching engine decided. Every event carries the engine's sequence number and the sim time at which
 * it happened.
 *
 * <p>The interface is sealed so a {@code switch} over events must handle every type: adding an event makes the
 * compiler point at every place that needs updating.
 */
public sealed interface ExchangeEvent
        permits OrderAccepted, OrderRejected, OrderCancelled, OrderModified, Trade, BookUpdate, SessionStateChanged {

    /** Strictly increasing event sequence; also the event ID Kafka consumers deduplicate on. */
    long seq();

    /** Sim time in microseconds since the Unix epoch. */
    long simTime();
}
