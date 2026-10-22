package org.rcs3000.resource.vehicle;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.rcs3000.resource.vehicle.domain.HistorySampler;
import org.rcs3000.resource.vehicle.domain.VehicleSample;

class HistorySamplerTest {

    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");

    private final HistorySampler sampler = new HistorySampler(0.5, Duration.ofSeconds(1), Duration.ofSeconds(60));

    @Test
    void aStandingVehicleIsSampledOncePerMaxInterval() {
        assertThat(sampler.accept(sample("A", 0, 0, 0, 90, "P1"))).isTrue();
        assertThat(sampler.accept(sample("A", 1, 0, 0, 90, "P1"))).isFalse();
        assertThat(sampler.accept(sample("A", 59, 0, 0, 90.4, "P1"))).isFalse();
        assertThat(sampler.accept(sample("A", 60, 0, 0, 90.4, "P1"))).isTrue();
    }

    @Test
    void movementIsSampledByDistanceButAtMostOncePerSecond() {
        sampler.accept(sample("A", 0, 0, 0, 90, "P1"));

        assertThat(sampler.accept(sample("A", 0.5, 0.8, 0, 90, "P1"))).as("too soon").isFalse();
        assertThat(sampler.accept(sample("A", 1, 0.3, 0, 90, "P1"))).as("too close").isFalse();
        assertThat(sampler.accept(sample("A", 1, 0.6, 0, 90, "P1"))).isTrue();
        assertThat(sampler.accept(sample("A", 2.5, 1.2, 0, 90, "P1"))).isTrue();
    }

    @Test
    void nodeAndBatteryChangesAreAlwaysSampled() {
        sampler.accept(sample("A", 0, 0, 0, 90, "P1"));

        assertThat(sampler.accept(sample("A", 0.1, 0, 0, 90, "P2"))).isTrue();
        assertThat(sampler.accept(sample("A", 0.2, 0, 0, 89, "P2"))).isTrue();
        assertThat(sampler.accept(sample("A", 0.3, 0, 0, 88.5, "P2"))).isFalse();
    }

    @Test
    void vehiclesAreSampledIndependently() {
        assertThat(sampler.accept(sample("A", 0, 0, 0, 90, "P1"))).isTrue();
        assertThat(sampler.accept(sample("B", 0, 0, 0, 90, "P1"))).isTrue();
    }

    @Test
    void outOfOrderStatesAreIgnored() {
        sampler.accept(sample("A", 10, 0, 0, 90, "P1"));

        assertThat(sampler.accept(sample("A", 5, 3, 0, 50, "P9"))).isFalse();
    }

    private static VehicleSample sample(String code, double seconds, double x, double y, double battery, String node) {
        return new VehicleSample(code, T0.plusMillis((long) (seconds * 1000)), "DEMO", x, y, 0, battery, false, false, node,
                "ONLINE");
    }
}
