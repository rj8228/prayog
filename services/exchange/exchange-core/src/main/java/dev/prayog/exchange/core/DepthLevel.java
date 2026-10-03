package dev.prayog.exchange.core;

/** One aggregated price level: total open quantity and how many orders make it up. */
public record DepthLevel(long price, long quantity, int orderCount) {}
