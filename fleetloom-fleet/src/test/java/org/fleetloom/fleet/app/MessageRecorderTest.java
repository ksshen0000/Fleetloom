package org.fleetloom.fleet.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.fleetloom.fleet.core.MessageLog;
import org.fleetloom.fleet.core.MessageLog.Direction;
import org.fleetloom.vda5050.client.VehicleId;
import org.fleetloom.vda5050.v2.AgvPosition;
import org.fleetloom.vda5050.v2.State;
import org.fleetloom.vda5050.v2.Vda5050Topic;

class MessageRecorderTest {

    private static final VehicleId AGV = new VehicleId("FLEETLOOM-SIM", "AGV-1");
    private static final byte[] JSON = "{}".getBytes(StandardCharsets.UTF_8);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-18T08:00:00Z"));
    private final List<String> logged = new CopyOnWriteArrayList<>();
    private final MessageRecorder recorder = new MessageRecorder(new MessageLog() {
        @Override
        public void append(String vehicleCode, Direction direction, String topic, String payload) {
            logged.add(vehicleCode + " " + direction + " " + topic);
        }

        @Override
        public List<Entry> find(String vehicleCode, Instant from, Instant to, int limit) {
            return List.of();
        }
    }, clock, Duration.ofSeconds(60));

    @Test
    void commandsAndConnectionsAreAlwaysLogged() {
        recorder.sent(AGV, Vda5050Topic.ORDER, null, JSON);
        recorder.sent(AGV, Vda5050Topic.ORDER, null, JSON);
        recorder.received(AGV, Vda5050Topic.CONNECTION, null, JSON);

        assertThat(logged).containsExactly("AGV-1 OUT order", "AGV-1 OUT order", "AGV-1 IN connection");
    }

    @Test
    void visualizationIsNotLogged() {
        recorder.received(AGV, Vda5050Topic.VISUALIZATION, null, JSON);

        assertThat(logged).isEmpty();
    }

    @Test
    void statesThatOnlyMoveAreLoggedOncePerInterval() {
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "A", 0), JSON);
        clock.advance(Duration.ofSeconds(1));
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "A", 1), JSON);
        clock.advance(Duration.ofSeconds(1));
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "B", 2), JSON);
        clock.advance(Duration.ofSeconds(30));
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "B", 3), JSON);
        clock.advance(Duration.ofSeconds(30));
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "B", 4), JSON);

        assertThat(logged).hasSize(3);
    }

    @Test
    void eachVehicleIsTrackedSeparately() {
        recorder.received(AGV, Vda5050Topic.STATE, state("O1", "A", 0), JSON);
        recorder.received(new VehicleId("FLEETLOOM-SIM", "AGV-2"), Vda5050Topic.STATE, state("O1", "A", 0), JSON);

        assertThat(logged).containsExactly("AGV-1 IN state", "AGV-2 IN state");
    }

    private static State state(String orderId, String lastNode, double x) {
        return State.of(null, orderId, 0, lastNode, 0, false, false, false, null, null, null, null,
                new AgvPosition(x, 0, 0, "WH1", null, true, null, null), null, null, null, null, null, null);
    }
}
