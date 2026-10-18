package org.rcs3000.vda5050.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.vda5050.transport.Qos;
import org.rcs3000.vda5050.transport.inmemory.InMemoryBroker;
import org.rcs3000.vda5050.transport.inmemory.InMemoryTransport;
import org.rcs3000.vda5050.v2.Connection;
import org.rcs3000.vda5050.v2.ConnectionState;
import org.rcs3000.vda5050.v2.Header;
import org.rcs3000.vda5050.v2.InstantActions;
import org.rcs3000.vda5050.v2.Order;
import org.rcs3000.vda5050.v2.SampleMessages;
import org.rcs3000.vda5050.v2.State;

class ClientRoundTripTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");
    private static final VehicleId AGV = new VehicleId("RCS3000-SIM", "AGV-001");

    private final InMemoryBroker broker = new InMemoryBroker();
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final List<Order> orders = new CopyOnWriteArrayList<>();
    private final List<InstantActions> instantActions = new CopyOnWriteArrayList<>();
    private final RecordingListener master = new RecordingListener();

    private InMemoryTransport agvTransport;
    private AgvClient agv;
    private MasterClient fleet;

    @BeforeEach
    void setUp() {
        fleet = new MasterClient(broker.newClient(), "uagv", clock);
        fleet.start(master);
        agvTransport = broker.newClient();
        agv = new AgvClient(agvTransport, "uagv", AGV.manufacturer(), AGV.serialNumber(), clock);
        agv.start(orders::add, instantActions::add);
    }

    @Test
    void vehicleAnnouncesOnlineRetained() {
        assertThat(master.connections).extracting(Connection::connectionState).containsExactly(ConnectionState.ONLINE);
        assertThat(broker.retainedMessage("uagv/v2/RCS3000-SIM/AGV-001/connection")).isNotNull();
    }

    @Test
    void lateFleetControlStillLearnsTheConnectionState() {
        RecordingListener late = new RecordingListener();
        new MasterClient(broker.newClient(), "uagv", clock).start(late);

        assertThat(late.connections).extracting(Connection::connectionState).containsExactly(ConnectionState.ONLINE);
    }

    @Test
    void orderReachesTheVehicleWithAStampedHeader() {
        Order order = SampleMessages.order().withHeader(Header.UNSTAMPED);

        fleet.sendOrder(AGV, order);
        fleet.sendOrder(AGV, order);

        assertThat(orders).hasSize(2);
        assertThat(orders.getFirst().nodes()).isEqualTo(order.nodes());
        assertThat(orders).extracting(Order::headerId).containsExactly(0L, 1L);
        assertThat(orders.getFirst()).extracting(Order::manufacturer, Order::serialNumber, Order::version, Order::timestamp)
                .containsExactly("RCS3000-SIM", "AGV-001", "2.1.0", NOW);
    }

    @Test
    void ordersForOtherVehiclesAreNotDelivered() {
        fleet.sendOrder(new VehicleId("RCS3000-SIM", "AGV-002"), SampleMessages.order());

        assertThat(orders).isEmpty();
    }

    @Test
    void instantActionsReachTheVehicle() {
        fleet.sendInstantActions(AGV, SampleMessages.instantActions());

        assertThat(instantActions).singleElement()
                .satisfies(ia -> assertThat(ia.actions()).hasSize(2));
    }

    @Test
    void stateReachesFleetControlWithVehicleIdentity() {
        agv.publishState(SampleMessages.state());
        agv.publishState(SampleMessages.state());

        assertThat(master.states).hasSize(2);
        assertThat(master.vehicles).containsOnly(AGV);
        assertThat(master.states).extracting(State::headerId).containsExactly(0L, 1L);
    }

    @Test
    void cleanShutdownAnnouncesOffline() {
        agv.close();

        assertThat(master.connections).extracting(Connection::connectionState)
                .containsExactly(ConnectionState.ONLINE, ConnectionState.OFFLINE);
    }

    @Test
    void connectionLossTriggersBrokenWill() {
        agvTransport.simulateConnectionLoss();

        assertThat(master.connections).extracting(Connection::connectionState)
                .containsExactly(ConnectionState.ONLINE, ConnectionState.CONNECTIONBROKEN);
    }

    @Test
    void malformedMessagesAreDroppedWithoutBreakingTheSubscription() {
        InMemoryTransport rogue = broker.newClient();
        rogue.connect(null);
        rogue.publish("uagv/v2/RCS3000-SIM/AGV-001/order", "{not json".getBytes(), Qos.AT_MOST_ONCE, false);

        fleet.sendOrder(AGV, SampleMessages.order());

        assertThat(orders).hasSize(1);
    }

    private static final class RecordingListener implements MasterListener {

        final List<Connection> connections = new CopyOnWriteArrayList<>();
        final List<State> states = new CopyOnWriteArrayList<>();
        final List<VehicleId> vehicles = new CopyOnWriteArrayList<>();

        @Override
        public void onConnection(VehicleId vehicle, Connection connection) {
            connections.add(connection);
        }

        @Override
        public void onState(VehicleId vehicle, State state) {
            vehicles.add(vehicle);
            states.add(state);
        }
    }
}
