package dev.prayog.exchange.app.admin;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * What the admin wants the simulated traders to do. The traders poll it ({@code GET /api/v1/simulation}) about once a
 * second and adapt: switch scenario, pause (cancel everything and stop trading), or apply a one-off price jump
 * ("news"). Each change bumps {@code version}; each jump has an id so it is applied exactly once. In memory only:
 * after a restart the traders return to their configured defaults.
 */
public final class SimulationControl {

    public static final Set<String> SCENARIOS = Set.of("calm", "volatile");

    /** A one-off move of a symbol's fair value by {@code percent} (e.g. -2.5). {@code symbol} null = every symbol. */
    public record Jump(long id, String symbol, double percent) {}

    public record State(long version, String scenario, boolean paused, List<Jump> jumps) {}

    private final Deque<Jump> jumps = new ArrayDeque<>();
    private long version;
    private long nextJumpId = 1;
    private String scenario; // null: whatever the traders were started with
    private boolean paused;

    public synchronized State state() {
        return new State(version, scenario, paused, List.copyOf(jumps));
    }

    public synchronized State setScenario(String name) {
        if (!SCENARIOS.contains(name)) {
            throw new IllegalArgumentException("scenario must be one of " + SCENARIOS);
        }
        scenario = name;
        version++;
        return state();
    }

    public synchronized State setPaused(boolean value) {
        paused = value;
        version++;
        return state();
    }

    public synchronized State jump(String symbol, double percent) {
        if (Double.isNaN(percent) || Math.abs(percent) > 8) {
            throw new IllegalArgumentException("percent must be between -8 and 8 (the band is 10%)");
        }
        jumps.addLast(new Jump(nextJumpId++, symbol, percent));
        while (jumps.size() > 20) {
            jumps.removeFirst();
        }
        version++;
        return state();
    }
}
