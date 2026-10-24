package org.fleetloom.fleet.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.fleetloom.contracts.fleet.ConnectionStatus;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.vda5050.client.VehicleId;
import org.fleetloom.vda5050.v2.Connection;
import org.fleetloom.vda5050.v2.ConnectionState;
import org.fleetloom.vda5050.v2.Header;
import org.fleetloom.vda5050.v2.SampleMessages;

class VehicleSessionTest {

    private static final Instant T0 = Instant.parse("2026-10-18T08:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final VehicleSession session = new VehicleSession("AGV-001", new VehicleId("FLEETLOOM-SIM", "AGV-001"));

    @Test
    void firstContactReportsTheConnection() {
        assertThat(session.onConnection(connection(ConnectionState.ONLINE), T0)).hasValueSatisfying(e -> {
            assertThat(e.previous()).isNull();
            assertThat(e.current()).isEqualTo(ConnectionStatus.ONLINE);
        });
        assertThat(session.onConnection(connection(ConnectionState.ONLINE), T0)).isEmpty();
    }

    @Test
    void silentVehicleIsMarkedBrokenAfterTheTimeout() {
        session.onState(SampleMessages.state(), T0);

        assertThat(session.checkHeartbeat(T0.plusSeconds(5), TIMEOUT)).isEmpty();
        assertThat(session.checkHeartbeat(T0.plusSeconds(6), TIMEOUT))
                .map(VehicleConnectionChangedEvent::current).contains(ConnectionStatus.CONNECTION_BROKEN);
        assertThat(session.checkHeartbeat(T0.plusSeconds(7), TIMEOUT)).isEmpty();

        assertThat(session.onState(SampleMessages.state(), T0.plusSeconds(8)))
                .map(VehicleConnectionChangedEvent::current).contains(ConnectionStatus.ONLINE);
    }

    @Test
    void offlineVehicleIsNotCheckedForHeartbeats() {
        session.onState(SampleMessages.state(), T0);
        session.onConnection(connection(ConnectionState.OFFLINE), T0.plusSeconds(1));

        assertThat(session.checkHeartbeat(T0.plusSeconds(60), TIMEOUT)).isEmpty();
        assertThat(session.connection()).isEqualTo(ConnectionStatus.OFFLINE);
    }

    @Test
    void statusMirrorsTheVehicleState() {
        session.onState(SampleMessages.state(), T0);

        VehicleStatusDto status = session.status();

        assertThat(status.connection()).isEqualTo(ConnectionStatus.ONLINE);
        assertThat(status.lastSeen()).isEqualTo(T0);
        assertThat(status.mapCode()).isEqualTo("WH1");
        assertThat(status.x()).isEqualTo(2.5);
        assertThat(status.velocity()).isEqualTo(1.0);
        assertThat(status.orderId()).isEqualTo("order-1");
        assertThat(status.lastNodeId()).isEqualTo("A");
        assertThat(status.remainingNodes()).extracting(VehicleStatusDto.NodeRef::nodeId).containsExactly("B");
        assertThat(status.battery()).isEqualTo(87.5);
        assertThat(status.loadIds()).containsExactly("PALLET-9");
        assertThat(status.actions()).singleElement().satisfies(a -> assertThat(a.status()).isEqualTo("WAITING"));
        assertThat(status.errors()).singleElement().satisfies(e -> assertThat(e.level()).isEqualTo("WARNING"));
    }

    @Test
    void statusBeforeAnyStateIsEmptyButValid() {
        session.onConnection(connection(ConnectionState.ONLINE), T0);

        VehicleStatusDto status = session.status();

        assertThat(status.positionInitialized()).isFalse();
        assertThat(status.remainingNodes()).isEmpty();
        assertThat(status.orderId()).isNull();
    }

    private static Connection connection(ConnectionState state) {
        return new Connection(Header.UNSTAMPED, state);
    }
}
