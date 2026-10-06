package dev.prayog.posttrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.Side;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class PositionMathTest {

    private static PositionMath.Position fill(PositionMath.Position p, Side side, long qty, long price) {
        return PositionMath.apply(p, side, qty, price).position();
    }

    @Test
    void buyingTwiceAveragesTheCost() {
        var p = fill(PositionMath.FLAT, Side.BUY, 10, 10_000); // 10 @ 100.00
        p = fill(p, Side.BUY, 10, 11_000); // 10 @ 110.00
        assertThat(p.quantity()).isEqualTo(20);
        assertThat(p.cost()).isEqualTo(210_000); // average 105.00
    }

    @Test
    void sellingPartOfALongRealisesAgainstTheAverage() {
        var p = fill(PositionMath.FLAT, Side.BUY, 10, 10_000);
        p = fill(p, Side.BUY, 10, 11_000);
        PositionMath.Applied sold = PositionMath.apply(p, Side.SELL, 5, 12_000);
        assertThat(sold.realised()).isEqualTo(5 * 12_000 - 52_500); // 75.00 rupees
        assertThat(sold.position().quantity()).isEqualTo(15);
        assertThat(sold.position().cost()).isEqualTo(157_500);
        assertThat(PositionMath.unrealised(sold.position(), 10_000)).isEqualTo(150_000 - 157_500); // -75.00
    }

    @Test
    void aShortIsOpenedAndCoveredTheSameWay() {
        var p = fill(PositionMath.FLAT, Side.SELL, 4, 20_000); // short 4 @ 200
        assertThat(p.quantity()).isEqualTo(-4);
        assertThat(p.cost()).isEqualTo(-80_000);
        PositionMath.Applied covered = PositionMath.apply(p, Side.BUY, 4, 19_000);
        assertThat(covered.realised()).isEqualTo(4_000); // sold at 200, bought back at 190
        assertThat(covered.position()).isEqualTo(PositionMath.FLAT);
    }

    @Test
    void aFillThroughZeroClosesThenOpensAtTheTradePrice() {
        var p = fill(PositionMath.FLAT, Side.BUY, 3, 10_000);
        PositionMath.Applied flipped = PositionMath.apply(p, Side.SELL, 5, 10_500);
        assertThat(flipped.realised()).isEqualTo(3 * 500);
        assertThat(flipped.position().quantity()).isEqualTo(-2);
        assertThat(flipped.position().cost()).isEqualTo(-21_000);
    }

    @Test
    void anOddSplitKeepsEveryPaisa() {
        var p = fill(PositionMath.FLAT, Side.BUY, 3, 10_001); // cost 30,003
        p = fill(p, Side.BUY, 1, 10_000); // 4 shares, cost 40,003: 10,000.75 each
        PositionMath.Applied a = PositionMath.apply(p, Side.SELL, 1, 10_000);
        PositionMath.Applied b = PositionMath.apply(a.position(), Side.SELL, 3, 10_000);
        // The truncated share of cost stays with the rest of the position, so the total realised is exact.
        assertThat(a.realised() + b.realised()).isEqualTo(40_000 - 40_003);
        assertThat(b.position()).isEqualTo(PositionMath.FLAT);
    }

    /** Whatever the order of fills: realised minus open cost is exactly the cash that changed hands. */
    @Property(tries = 500)
    void cashIdentityHolds(@ForAll("fills") List<Fill> fills) {
        var p = PositionMath.FLAT;
        long realised = 0;
        long cash = 0;
        long quantity = 0;
        for (Fill f : fills) {
            PositionMath.Applied applied = PositionMath.apply(p, f.side(), f.quantity(), f.price());
            p = applied.position();
            realised += applied.realised();
            long signed = f.side() == Side.BUY ? f.quantity() : -f.quantity();
            cash -= signed * f.price();
            quantity += signed;
        }
        assertThat(p.quantity()).isEqualTo(quantity);
        assertThat(realised - p.cost()).isEqualTo(cash);
        if (p.quantity() == 0) {
            assertThat(p.cost()).isZero();
        }
        // Mark-to-market total equals cash plus the open quantity at the mark.
        long mark = 10_000;
        assertThat(realised + PositionMath.unrealised(p, mark)).isEqualTo(cash + p.quantity() * mark);
    }

    record Fill(Side side, long quantity, long price) {}

    @Provide
    Arbitrary<List<Fill>> fills() {
        Arbitrary<Fill> fill = Combinators.combine(
                        Arbitraries.of(Side.class),
                        Arbitraries.longs().between(1, 300),
                        Arbitraries.longs().between(1_800, 2_200).map(t -> t * 5))
                .as(Fill::new);
        return fill.list().ofMinSize(1).ofMaxSize(60);
    }
}
