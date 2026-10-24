package org.fleetloom.simulator.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.fleetloom.simulator.scenario.ScenarioRunner;
import org.fleetloom.vda5050.client.MasterClient;
import org.fleetloom.vda5050.client.MasterListener;
import org.fleetloom.vda5050.client.VehicleId;
import org.fleetloom.vda5050.transport.hivemq.HiveMqTransport;
import org.fleetloom.vda5050.v2.Edge;
import org.fleetloom.vda5050.v2.Header;
import org.fleetloom.vda5050.v2.Node;
import org.fleetloom.vda5050.v2.Order;
import org.fleetloom.vda5050.v2.State;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The simulator on its own, as another VDA 5050 fleet manager would use it: no Fleetloom service, the
 * map from a file, orders that name the points without their positions.
 */
@SpringBootTest(properties = {
        "fleetloom.simulator.platform.enabled=false",
        "fleetloom.simulator.manufacturer=ACME",
        "fleetloom.simulator.map.file=src/test/resources/standalone-map.json",
        "fleetloom.simulator.vehicles[0].serial-number=V1",
        "fleetloom.simulator.vehicles[0].initial-point=A",
        "fleetloom.simulator.time-scale=10"
})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class StandaloneSimulatorIntegrationTest {

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

    @Autowired
    private ApplicationContext context;

    private MasterClient master;

    @AfterEach
    void tearDown() {
        if (master != null) {
            master.close();
        }
    }

    @Test
    void drivesOrdersOfAnyFleetManagerOnAMapFromAFile() throws Exception {
        List<State> states = new CopyOnWriteArrayList<>();
        master = new MasterClient(new HiveMqTransport(MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883), "other-fm"),
                "uagv", Clock.systemUTC());
        master.start(new MasterListener() {
            @Override
            public void onState(VehicleId vehicle, State state) {
                states.add(state);
            }
        });

        // point ids only: the positions come from the map file
        Order order = new Order(Header.UNSTAMPED, "fm-1", 0,
                List.of(new Node("A", 0, null, true, null, List.of()), new Node("B", 2, null, true, null, List.of()),
                        new Node("C", 4, null, true, null, List.of())),
                List.of(Edge.of("A-B", 1, true, "A", "B", null, null, null),
                        Edge.of("B-C", 3, true, "B", "C", null, null, null)));
        master.sendOrder(new VehicleId("ACME", "V1"), order);

        await().atMost(Duration.ofSeconds(20)).until(() -> !states.isEmpty()
                && "C".equals(states.getLast().lastNodeId()) && !states.getLast().driving());
        State last = states.getLast();
        assertThat(last.agvPosition().mapId()).isEqualTo("HALL1");
        assertThat(last.agvPosition().x()).isEqualTo(6.0);
        assertThat(last.agvPosition().y()).isEqualTo(4.0);

        mvc.perform(get("/api/simulator/v1/map/points"))
                .andExpect(jsonPath("$.data[0]").value("A"))
                .andExpect(jsonPath("$.data.length()").value(2));
        // nothing of Fleetloom is there
        assertThat(context.getBeansOfType(ScenarioRunner.class)).isEmpty();
        mvc.perform(get("/api/simulator/v1/scenarios/current")).andExpect(status().isNotFound());
    }
}
