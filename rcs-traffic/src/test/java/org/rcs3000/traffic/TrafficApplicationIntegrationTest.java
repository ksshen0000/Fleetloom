package org.rcs3000.traffic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.traffic.core.TestMaps;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * The traffic application wired for real: HTTP clients to the map and fleet services (stubbed by a
 * local HTTP server), vehicle state arriving as events through Valkey. Skipped when Docker is not
 * available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class TrafficApplicationIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> FLEET_REQUESTS = new CopyOnWriteArrayList<>();
    private static final HttpServer STUB = startStub();

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        String url = "http://localhost:" + STUB.getAddress().getPort();
        registry.add("rcs.traffic.fleet-url", () -> url);
        registry.add("rcs.traffic.resource-url", () -> url);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @AfterAll
    static void stopStub() {
        STUB.stop(0);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RedisConnectionFactory connectionFactory;

    @Test
    void plansReservesAndSendsTheRouteToTheFleetService() throws Exception {
        try (ValkeyEventBus fleetSide = new ValkeyEventBus(connectionFactory, JSON, "fleet-stub", Clock.systemUTC())) {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                fleetSide.publish(VehicleStateUpdatedEvent.TYPE, new VehicleStateUpdatedEvent(idleAt("P00")));
                mvc.perform(get("/api/traffic/v1/reservations"))
                        .andExpect(jsonPath("$.data[0].resource").value("NODE:P00"));
            });
        }

        mvc.perform(post("/api/traffic/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleCode\": \"AGV-1\", \"targetPoint\": \"P02\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("MOVING"))
                .andExpect(jsonPath("$.data.nodes.length()").value(3));

        assertThat(FLEET_REQUESTS).singleElement().satisfies(body -> {
            assertThat(body).contains("\"pointCode\":\"P02\"");
            assertThat(body).contains("\"mapCode\":\"TEST\"");
        });
        mvc.perform(post("/api/traffic/v1/costs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapCode\": \"TEST\", \"from\": [\"P00\"], \"to\": [\"P02\"]}"))
                .andExpect(jsonPath("$.data.seconds[0][0]").value(8.0));
    }

    private static VehicleStatusDto idleAt(String point) {
        return new VehicleStatusDto("AGV-1", "RCS3000-SIM", "AGV-1", ConnectionStatus.ONLINE, Instant.now(), "TEST",
                0, 0, 0, true, 0, false, false, "AUTOMATIC", "", 0, point, 0, List.of(), 100, false, List.of(), List.of(),
                List.of());
    }

    private static HttpServer startStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/api/resource/v1/maps/TEST/active",
                    exchange -> respond(exchange, ApiResponse.ok(TestMaps.grid(1, 3, 4))));
            server.createContext("/api/fleet/v1/vehicles/AGV-1/orders", exchange -> {
                FLEET_REQUESTS.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, ApiResponse.ok(new OrderRefDto("AGV-1", "AGV-1-test", 0)));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, Object body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
