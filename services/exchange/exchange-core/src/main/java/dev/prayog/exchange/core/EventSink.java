package dev.prayog.exchange.core;

import dev.prayog.contracts.event.ExchangeEvent;

/** Receives events in the order the engine produces them. Called on the matching thread; must not block. */
@FunctionalInterface
public interface EventSink {

    void accept(ExchangeEvent event);
}
