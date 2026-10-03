package dev.prayog.contracts;

/** Why a new, cancel or modify request was refused. Must match {@code rejectReason} in common.schema.json. */
public enum RejectReason {
    UNKNOWN_SYMBOL,
    INVALID_PRICE,
    INVALID_QUANTITY,
    PRICE_NOT_ON_TICK,
    PRICE_OUTSIDE_BAND,
    SESSION_NOT_OPEN,
    UNKNOWN_ORDER,
    DUPLICATE_CLIENT_ORDER_ID,
    RATE_LIMITED,
    ACCOUNT_DISABLED
}
