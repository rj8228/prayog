package dev.prayog.exchange.core;

import static org.assertj.core.api.Assertions.assertThat;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import org.junit.jupiter.api.Test;

/** Proves JUnit, AssertJ and jqwik run together under Surefire. Remove once real tests exist (S4). */
class ToolchainSmokeTest {

    @Test
    void junitRuns() {
        assertThat(1 + 1).isEqualTo(2);
    }

    @Property(tries = 100)
    void jqwikRuns(@ForAll int a, @ForAll int b) {
        assertThat(Math.addExact((long) a, (long) b)).isEqualTo((long) b + a);
    }
}
