package dev.prayog.exchange.core;

/** Ops enables or disables an account. Disabling is the per-account kill switch. */
public record SetAccountEnabled(long accountId, boolean enabled) implements Command {}
