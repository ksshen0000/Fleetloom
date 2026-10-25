package org.fleetloom.simulator.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SimulatorPropertiesTest {

    @Test
    void vehiclesBindFromTheCommandLineForm() {
        MapConfigurationPropertySource source = new MapConfigurationPropertySource(Map.of(
                "fleetloom.simulator.vehicles[0].serial-number", "AGV-001",
                "fleetloom.simulator.vehicles[0].initial-point", "P10",
                "fleetloom.simulator.vehicles[1].serial-number", "AGV-002",
                "fleetloom.simulator.vehicles[1].max-speed", "2.0"));

        SimulatorProperties p = new Binder(source).bind("fleetloom.simulator", SimulatorProperties.class).get();

        assertThat(p.vehicles()).hasSize(2);
        assertThat(p.vehicles().get(0).initialPoint()).isEqualTo("P10");
        assertThat(p.vehicles().get(0).maxSpeed()).isNull();
        assertThat(p.vehicles().get(1).maxSpeed()).isEqualTo(2.0);
    }
}
