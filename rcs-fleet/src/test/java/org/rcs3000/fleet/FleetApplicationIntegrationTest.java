package org.rcs3000.fleet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.fleet.infra.ValkeyVehicleStatusCache;
import org.rcs3000.simulator.core.Pose;
import org.rcs3000.simulator.core.SimulatedAgv;
import org.rcs3000.simulator.core.SimulationEngine;
import org.rcs3000.simulator.core.SiteMap;
import org.rcs3000.simulator.core.VehicleProfile;
import org.rcs3000.simulator.core.VirtualVehicle;
import org.rcs3000.vda5050.client.AgvClient;
import org.rcs3000.vda5050.transport.hivemq.HiveMqTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The fleet application with real Mosquitto and Valkey, commanding a simulated vehicle that runs in
 * this test over MQTT. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class FleetApplicationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> MOSQUITTO = new GenericContainer<>("eclipse-mosquitto:2")
            .withCopyToContainer(Transferable.of("listener 1883\nallow_anonymous true\n"),
                    "/mosquitto/config/mosquitto.conf")
            .withExposedPorts(1883);

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("rcs.fleet.mqtt.host", MOSQUITTO::getHost);
        registry.add("rcs.fleet.mqtt.port", () -> MOSQUITTO.getMappedPort(1883));
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    private SimulationEngine engine;

    @BeforeEach
    void startVehicle() {
        VehicleProfile profile = new VehicleProfile(1.5, 1.0, 1.0, Math.PI, 0.1, 0.01, 0.0, 1.0, 0.5, Map.of(), Set.of());
        VirtualVehicle vehicle = new VirtualVehicle("IT-1", profile, SiteMap.empty("WH1"), new Pose(0, 0, 0), "");
        AgvClient client = new AgvClient(new HiveMqTransport(MOSQUITTO.getHost(), MOSQUITTO.getMappedPort(1883), "it-agv"),
                "uagv", "RCS3000-SIM", "IT-1", Clock.systemUTC());
        SimulatedAgv agv = new SimulatedAgv(vehicle, client, 1.0, 0.1);
        agv.connect();
        engine = new SimulationEngine(20, 5.0);
        engine.add(agv);
        engine.start();
    }

    @AfterEach
    void stopVehicle() {
        engine.close();
    }

    @Test
    void commandsAVehicleOverMqttAndPublishesItsState() throws Exception {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get("/api/fleet/v1/vehicles/IT-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connection").value("ONLINE")));

        mvc.perform(post("/api/fleet/v1/vehicles/IT-1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"mapCode": "WH1",
                         "nodes": [{"pointCode": "A", "x": 0, "y": 0, "released": true},
                                   {"pointCode": "B", "x": 4, "y": 0, "released": true}],
                         "edges": [{"pathCode": "AB", "released": true}]}
                        """))
                .andExpect(status().isOk());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> mvc.perform(get("/api/fleet/v1/vehicles/IT-1"))
                .andExpect(jsonPath("$.data.lastNodeId").value("B"))
                .andExpect(jsonPath("$.data.driving").value(false))
                .andExpect(jsonPath("$.data.x").value(4.0)));

        assertThat(redis.opsForHash().get(ValkeyVehicleStatusCache.KEY, "IT-1")).asString().contains("\"lastNodeId\":\"B\"");
        assertThat(redis.opsForStream().size(ValkeyEventBus.streamKey(VehicleStateUpdatedEvent.TYPE))).isPositive();

        mvc.perform(get("/api/fleet/v1/vehicles/IT-1/messages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.direction == 'OUT' && @.topic == 'order')]").exists())
                .andExpect(jsonPath("$.data[?(@.direction == 'IN' && @.topic == 'state')]").exists())
                .andExpect(jsonPath("$.data[?(@.topic == 'visualization')]").doesNotExist());
        mvc.perform(get("/api/fleet/v1/vehicles/IT-1/messages").param("to", "2000-01-01T00:00:00Z"))
                .andExpect(jsonPath("$.data").isEmpty());
    }
}
