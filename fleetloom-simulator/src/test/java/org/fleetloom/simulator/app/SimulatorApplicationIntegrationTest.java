package org.fleetloom.simulator.app;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.fleetloom.vda5050.client.MasterClient;
import org.fleetloom.vda5050.client.MasterListener;
import org.fleetloom.vda5050.client.VehicleId;
import org.fleetloom.vda5050.transport.hivemq.HiveMqTransport;
import org.fleetloom.vda5050.v2.Connection;
import org.fleetloom.vda5050.v2.Edge;
import org.fleetloom.vda5050.v2.Header;
import org.fleetloom.vda5050.v2.Node;
import org.fleetloom.vda5050.v2.NodePosition;
import org.fleetloom.vda5050.v2.Order;
import org.fleetloom.vda5050.v2.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The simulator application against a real Mosquitto: fleet control sends an order over MQTT and
 * watches the vehicle drive it. Skipped when Docker is not available.
 */
@SpringBootTest(properties = {
        "fleetloom.simulator.vehicles[0].serial-number=SIM-1",
        "fleetloom.simulator.vehicles[0].x=0",
        "fleetloom.simulator.vehicles[0].y=0",
        "fleetloom.simulator.time-scale=10",
        "fleetloom.simulator.platform.restore-vehicles=false"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SimulatorApplicationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MOSQUITTO = new GenericContainer<>("eclipse-mosquitto:2")
            .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\n"),
                    "/mosquitto/config/mosquitto.conf")
            .withExposedPorts(1883);

    @DynamicPropertySource
    static void mqtt(DynamicPropertyRegistry registry) {
        registry.add("fleetloom.simulator.mqtt.host", MOSQUITTO::getHost);
        registry.add("fleetloom.simulator.mqtt.port", () -> MOSQUITTO.getMappedPort(1883));
    }

    @Autowired
    private MockMvc mvc;

    private MasterClient fleet;

    @AfterEach
    void tearDown() {
        if (fleet != null) {
            fleet.close();
        }
    }

    @Test
    void drivesAnOrderSentOverMqtt() throws Exception {
        List<State> states = new CopyOnWriteArrayList<>();
        fleet = new MasterClient(new HiveMqTransport(MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883), "fleet-it"),
                "uagv", Clock.systemUTC());
        fleet.start(new MasterListener() {
            @Override
            public void onState(VehicleId vehicle, State state) {
                states.add(state);
            }
        });

        Order order = new Order(Header.UNSTAMPED, "it-1", 0,
                List.of(new Node("A", 0, null, true, NodePosition.of(0, 0, null, ""), List.of()),
                        new Node("B", 2, null, true, NodePosition.of(6, 0, null, ""), List.of())),
                List.of(Edge.of("AB", 1, true, "A", "B", null, null, null)));
        fleet.sendOrder(new VehicleId("FLEETLOOM-SIM", "SIM-1"), order);

        // 6 m at 1.5 m/s max with 0.5 m/s² ramps takes ~7 s of simulated time, under 1 s at 10x
        await().atMost(Duration.ofSeconds(10)).until(() -> !states.isEmpty()
                && states.getLast().lastNodeId().equals("B") && !states.getLast().driving());

        mvc.perform(get("/api/simulator/v1/vehicles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].serialNumber").value("SIM-1"))
                .andExpect(jsonPath("$.data[0].lastNodeId").value("B"))
                .andExpect(jsonPath("$.data[0].x").value(6.0));
    }

    @Test
    void vehiclesAreAddedAndRemovedAtRuntime() throws Exception {
        List<String> connections = new CopyOnWriteArrayList<>();
        fleet = new MasterClient(new HiveMqTransport(MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883), "fleet-it-2"),
                "uagv", Clock.systemUTC());
        fleet.start(new MasterListener() {
            @Override
            public void onConnection(VehicleId vehicle, Connection connection) {
                connections.add(vehicle.serialNumber() + " " + connection.connectionState());
            }
        });

        mvc.perform(post("/api/simulator/v1/vehicles").contentType(MediaType.APPLICATION_JSON).content("""
                        [{"serialNumber": "SIM-2", "x": 3, "y": 4, "battery": 40},
                         {"serialNumber": "SIM-3", "x": 0, "y": 8}]
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].battery").value(40.0))
                .andExpect(jsonPath("$.data[1].battery").value(100.0));
        await().atMost(Duration.ofSeconds(10)).until(() -> connections.contains("SIM-2 ONLINE"));

        mvc.perform(post("/api/simulator/v1/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"serialNumber\": \"SIM-2\"}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/simulator/v1/vehicles").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"serialNumber\": \"bad name\"}]"))
                .andExpect(status().isBadRequest());

        mvc.perform(delete("/api/simulator/v1/vehicles/SIM-2")).andExpect(status().isOk());
        mvc.perform(delete("/api/simulator/v1/vehicles/SIM-3")).andExpect(status().isOk());
        mvc.perform(delete("/api/simulator/v1/vehicles/SIM-2")).andExpect(status().isNotFound());
        await().atMost(Duration.ofSeconds(10)).until(() -> connections.contains("SIM-2 OFFLINE"));
        mvc.perform(get("/api/simulator/v1/vehicles"))
                .andExpect(jsonPath("$.data[?(@.serialNumber == 'SIM-2')]").doesNotExist());
        // no map configured here
        mvc.perform(get("/api/simulator/v1/map/points")).andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void scenariosAreCheckedBeforeTheyRun() throws Exception {
        mvc.perform(get("/api/simulator/v1/scenarios/current"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/simulator/v1/scenarios").contentType("application/yaml")
                        .content("orders: [{type: MOVE}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("targetPoints")));
    }

    @Test
    void timeScaleCanBeChanged() throws Exception {
        mvc.perform(put("/api/simulator/v1/time-scale").contentType(MediaType.APPLICATION_JSON).content("{\"value\": 2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2.0));
        mvc.perform(put("/api/simulator/v1/time-scale").contentType(MediaType.APPLICATION_JSON).content("{\"value\": 10}"))
                .andExpect(status().isOk());
    }
}
