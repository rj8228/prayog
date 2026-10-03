package dev.prayog.contracts;

/** Why the open part of an order was removed. Must match {@code cancelReason} in common.schema.json. */
public enum CancelReason {
    CLIENT_REQUEST,
    SELF_TRADE_PREVENTION,
    /** A market order's unfilled remainder: nothing left to match within the band. */
    NO_LIQUIDITY,
    /** A modify reduced quantity to the filled amount or below (BUILD_PLAN 16.3). */
    MODIFIED_TO_ZERO,
    KILL_SWITCH,
    /** A DAY order still open when the session closed. */
    EXPIRED
}
