package dev.prayog.posttrade.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChargesTest {

    // Brokerage 0.03% capped at 20 rupees, fees 0.0035%.
    private final Charges charges = new Charges(300, 2_000, 35);

    @Test
    void smallTradesPayPercentageBrokerage() {
        // 10 x 150.00 = 1,500.00 rupees = 150,000 paise: brokerage 45 paise, fees 5.25 -> 5 paise
        assertThat(charges.on(150_000)).isEqualTo(45 + 5);
    }

    @Test
    void brokerageIsCapped() {
        // 1,000 x 4,000.00 = 40,00,000.00 rupees: 0.03% would be 1,200 rupees; capped at 20; fees 140 rupees
        assertThat(charges.on(400_000_000)).isEqualTo(2_000 + 14_000);
    }

    @Test
    void roundingIsHalfUpToTheNearestPaisa() {
        Charges fees = new Charges(0, 0, 5_000); // 0.5%
        assertThat(fees.on(99)).isZero(); // 0.495 -> 0
        assertThat(fees.on(100)).isEqualTo(1); // 0.5 -> 1
        assertThat(fees.on(300)).isEqualTo(2); // 1.5 -> 2
    }

    @Test
    void zeroValueCostsNothing() {
        assertThat(charges.on(0)).isZero();
    }
}
