package dev.prayog.exchange.core;

import static dev.prayog.exchange.core.EngineFixture.T0;
import static org.assertj.core.api.Assertions.assertThat;

import dev.prayog.contracts.SessionState;
import dev.prayog.contracts.event.ExchangeEvent;
import java.util.ArrayList;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

/**
 * ADR 0016: an engine restored from a snapshot is indistinguishable from the one that took it. For any random flow,
 * snapshot partway, restore into a fresh engine, run the rest through both: same events, same final state.
 */
class EngineSnapshotTest {

    private static final List<Instrument> INSTRUMENTS = List.of(EngineFixture.INSTRUMENT);

    @Property(tries = 300)
    void aRestoredEngineCarriesOnExactlyLikeTheOriginal(
            @ForAll("flows") List<Command> flow, @ForAll @IntRange(min = 0, max = 100) int cutPercent) {
        List<ExchangeEvent> original = new ArrayList<>();
        MatchingEngine a = new MatchingEngine(INSTRUMENTS, original::add);
        a.apply(new ClockTick(T0));
        a.apply(new SetRules(MatchingEngine.LATEST_RULES));
        a.apply(new SetSessionState(SessionState.OPEN));
        int cut = flow.size() * cutPercent / 100;
        flow.subList(0, cut).forEach(a::apply);

        EngineState state = a.snapshot();
        List<ExchangeEvent> restored = new ArrayList<>();
        MatchingEngine b = MatchingEngine.restore(INSTRUMENTS, null, state, restored::add);
        assertThat(b.snapshot())
                .as("restore then snapshot gives the same state")
                .isEqualTo(state);

        int mark = original.size();
        for (Command command : flow.subList(cut, flow.size())) {
            a.apply(command);
            b.apply(command);
        }
        assertThat(restored).isEqualTo(original.subList(mark, original.size()));
        assertThat(b.snapshot()).isEqualTo(a.snapshot());
        b.book(EngineFixture.ABC).checkInvariants();
    }

    @Provide
    Arbitrary<List<Command>> flows() {
        // The property test's flows (orders, cancels, modifies, ticks, duplicates, bad inputs), with kill switches and
        // session changes mixed in, so disabled accounts and the day's client order IDs are part of the state too.
        Arbitrary<Command> extra = Arbitraries.oneOf(
                Combinators.combine(Arbitraries.longs().between(1, 4), Arbitraries.of(true, false))
                        .as(SetAccountEnabled::new),
                Arbitraries.of(SessionState.class).map(SetSessionState::new));
        return Combinators.combine(
                        new MatchingEnginePropertiesTest().flows(), extra.list().ofMaxSize(6))
                .as((base, extras) -> {
                    List<Command> mixed = new ArrayList<>(base);
                    for (int i = 0; i < extras.size(); i++) {
                        mixed.add((i + 1) * mixed.size() / (extras.size() + 1), extras.get(i));
                    }
                    return mixed;
                });
    }
}
