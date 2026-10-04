package dev.prayog.exchange.app.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** What the simulated traders poll to follow the admin's live controls (role {@code bot} or {@code admin}). */
@RestController
public class SimulationFeed {

    private final SimulationControl simulation;

    public SimulationFeed(SimulationControl simulation) {
        this.simulation = simulation;
    }

    @GetMapping("/api/v1/simulation")
    SimulationControl.State state() {
        return simulation.state();
    }
}
