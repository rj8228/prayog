package dev.prayog.posttrade.ledger;

import dev.prayog.contracts.Side;

/**
 * Average-cost position keeping in exact integer paise (ADR 0015).
 *
 * <p>A position is a signed quantity and the signed total {@code cost} of that quantity (what was paid for a long,
 * minus what was received for a short). Keeping the <i>total</i> rather than a per-share average keeps it an integer:
 * when part of a position is closed, the share of cost removed is {@code cost * closed / quantity}, truncated, and the
 * leftover fraction of a paisa stays with the rest of the position. Nothing is ever lost to rounding:
 * {@code realised - cost} always equals the cash that changed hands, exactly.
 */
public final class PositionMath {

    public record Position(long quantity, long cost) {}

    /** A fill's effect: the new position and the P&L it realised (0 when it only opened or added). */
    public record Applied(Position position, long realised) {}

    public static final Position FLAT = new Position(0, 0);

    private PositionMath() {}

    public static Applied apply(Position p, Side side, long quantity, long price) {
        if (quantity <= 0 || price <= 0) {
            throw new IllegalArgumentException("quantity and price must be positive");
        }
        long signed = side == Side.BUY ? quantity : -quantity;
        if (p.quantity() == 0 || Long.signum(p.quantity()) == Long.signum(signed)) {
            // Opening or adding: cost grows by what was paid (or, short, received).
            return new Applied(
                    new Position(p.quantity() + signed, Math.addExact(p.cost(), Math.multiplyExact(signed, price))), 0);
        }
        // Reducing, closing or flipping.
        long held = Math.abs(p.quantity());
        long closed = Math.min(quantity, held);
        long direction = Long.signum(p.quantity()); // +1 long, -1 short
        long removedCost = Math.multiplyExact(p.cost(), closed) / held; // truncates toward zero
        long realised = direction * closed * price - removedCost;
        long quantityLeft = p.quantity() - direction * closed;
        long costLeft = p.cost() - removedCost;
        long opened = quantity - closed;
        if (opened == 0) {
            return new Applied(new Position(quantityLeft, costLeft), realised);
        }
        // Through zero: the rest opens a position the other way at the trade price.
        long openedSigned = Long.signum(signed) * opened;
        return new Applied(new Position(openedSigned, openedSigned * price), realised);
    }

    /** Mark-to-market P&L of the open position at {@code mark}. */
    public static long unrealised(Position p, long mark) {
        return Math.multiplyExact(p.quantity(), mark) - p.cost();
    }
}
