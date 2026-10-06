package dev.prayog.posttrade.ledger;

/**
 * The simple MVP charge model (BUILD_PLAN section 5): brokerage as a percentage of trade value with a cap, plus a
 * percentage for exchange and regulatory fees. Rates are in parts per million of the trade value (300 ppm = 0.03%),
 * so they stay integers. Each side of a trade pays on its own fill.
 *
 * @param brokeragePpm brokerage rate
 * @param brokerageCapPaise most brokerage a single fill pays
 * @param feesPpm exchange and regulatory fees
 */
public record Charges(long brokeragePpm, long brokerageCapPaise, long feesPpm) {

    /** Charges on a fill worth {@code valuePaise}, rounded half up to the paisa. */
    public long on(long valuePaise) {
        long brokerage = Math.min(ppm(valuePaise, brokeragePpm), brokerageCapPaise);
        return brokerage + ppm(valuePaise, feesPpm);
    }

    private static long ppm(long value, long rate) {
        return Math.addExact(Math.multiplyExact(value, rate), 500_000) / 1_000_000;
    }
}
