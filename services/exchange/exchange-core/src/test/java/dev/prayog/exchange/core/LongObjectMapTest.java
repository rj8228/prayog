package dev.prayog.exchange.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.junit.jupiter.api.Test;

class LongObjectMapTest {

    @Test
    void putGetRemove() {
        LongObjectMap<String> map = new LongObjectMap<>(4);
        assertThat(map.put(7, "a")).isNull();
        assertThat(map.put(7, "b")).isEqualTo("a");
        assertThat(map.get(7)).isEqualTo("b");
        assertThat(map.size()).isEqualTo(1);
        assertThat(map.remove(7)).isEqualTo("b");
        assertThat(map.remove(7)).isNull();
        assertThat(map.get(7)).isNull();
        assertThat(map.size()).isZero();
    }

    @Test
    void growsPastItsInitialCapacity() {
        LongObjectMap<Long> map = new LongObjectMap<>(2);
        for (long k = 1; k <= 10_000; k++) {
            map.put(k, k * 10);
        }
        assertThat(map.size()).isEqualTo(10_000);
        for (long k = 1; k <= 10_000; k++) {
            assertThat(map.get(k)).isEqualTo(k * 10);
        }
    }

    @Test
    void zeroIsNotAKey() {
        LongObjectMap<String> map = new LongObjectMap<>(4);
        assertThatThrownBy(() -> map.put(0, "x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(map.get(0)).isNull();
    }

    @Test
    void allocatesNothingOnPutGetRemoveOnceSized() {
        LongObjectMap<String> map = new LongObjectMap<>(1 << 12);
        String value = "v";
        for (int i = 0; i < 1_000; i++) { // warm up so the JIT compiles the methods
            map.put(i + 1, value);
            map.get(i + 1);
            map.remove(i + 1);
        }
        com.sun.management.ThreadMXBean threads =
                (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (long k = 1_000_000_007L; k < 1_000_001_007L; k++) { // large ids: a HashMap would box every one
            map.put(k, value);
            map.get(k);
            map.remove(k);
        }
        long allocated = threads.getCurrentThreadAllocatedBytes() - before;
        assertThat(allocated).isLessThan(1_000); // HashMap<Long, _> would allocate about 64 KB here
    }

    /** Any sequence of puts and removes leaves the map equal to a HashMap given the same sequence. */
    @Property(tries = 300)
    void behavesLikeAHashMap(@ForAll("operations") List<long[]> operations) {
        LongObjectMap<Long> map = new LongObjectMap<>(2);
        Map<Long, Long> reference = new HashMap<>();
        for (long[] op : operations) {
            long key = op[1];
            if (op[0] == 0) {
                assertThat(map.put(key, op[2])).isEqualTo(reference.put(key, op[2]));
            } else {
                assertThat(map.remove(key)).isEqualTo(reference.remove(key));
            }
            assertThat(map.size()).isEqualTo(reference.size());
        }
        for (long key = 1; key <= 64; key++) {
            assertThat(map.get(key)).isEqualTo(reference.get(key));
        }
        List<Long> values = new ArrayList<>();
        map.forEachValue(values::add);
        assertThat(values).containsExactlyInAnyOrderElementsOf(reference.values());
    }

    @Provide
    Arbitrary<List<long[]>> operations() {
        // Few distinct keys, so collisions, removals inside probe chains and re-inserts are common.
        Arbitrary<long[]> op = Combinators.combine(
                        Arbitraries.longs().between(0, 1),
                        Arbitraries.longs().between(1, 64),
                        Arbitraries.longs().between(-1_000, 1_000))
                .as((kind, key, value) -> new long[] {kind, key, value});
        return op.list().ofMaxSize(400);
    }
}
