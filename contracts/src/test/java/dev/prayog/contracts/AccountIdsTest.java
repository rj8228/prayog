package dev.prayog.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccountIdsTest {

    @Test
    void theIdIsStablePositiveAndDiffersByLabel() {
        long main = AccountIds.accountId("subject-1", "main");
        assertThat(main).isPositive().isEqualTo(AccountIds.accountId("subject-1", "main"));
        assertThat(AccountIds.accountId("subject-1", "mm")).isNotEqualTo(main);
        assertThat(AccountIds.accountId("subject-2", "main")).isNotEqualTo(main);
        // Pinned: changing the hash would move every account's history.
        assertThat(AccountIds.accountId("trader1", "main")).isEqualTo(5389308726394077169L);
    }

    @Test
    void labelsDefaultAndAreValidated() {
        assertThat(AccountIds.label(null)).isEqualTo("main");
        assertThat(AccountIds.label(" ")).isEqualTo("main");
        assertThat(AccountIds.label("algo-twap-infy")).isEqualTo("algo-twap-infy");
        assertThatThrownBy(() -> AccountIds.label("Not Valid!")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountIds.label("x".repeat(33))).isInstanceOf(IllegalArgumentException.class);
    }
}
