package dev.prayog.exchange.app.core;

import dev.prayog.contracts.event.ExchangeEvent;
import java.util.List;

/** What one command did, known once it is journaled: its input sequence and the events it produced, in order. */
public record CommandResult(long inputSeq, List<ExchangeEvent> events) {}
