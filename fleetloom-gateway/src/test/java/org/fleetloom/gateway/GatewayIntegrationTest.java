package org.fleetloom.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.crypto.spec.SecretKeySpec;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.fleetloom.common.event.valkey.ValkeyEventBus;
import org.fleetloom.contracts.audit.AuditRecordedEvent;
import org.fleetloom.gateway.push.PushHub;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.Disposable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The gateway in front of a stub service that echoes what it received, with a real Valkey for push.
 * Skipped when Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class GatewayIntegrationTest {

    static final String SECRET = "test-secret-0123456789abcdef-0123456789";

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    /** Answers every request with the method, path and caller headers it saw. */
    static final java.util.concurrent.atomic.AtomicInteger BACKEND_STATUS = new java.util.concurrent.atomic.AtomicInteger(200);
    static final HttpServer BACKEND = startBackend();

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("fleetloom.security.jwt-secret", () -> SECRET);
        String backend = "http://localhost:" + BACKEND.getAddress().getPort();
        for (String service : List.of("resource", "dispatch", "fleet", "traffic", "simulator")) {
            registry.add("fleetloom.gateway.services." + service, () -> backend);
        }
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.stop(0);
    }

    @LocalServerPort
    int port;

    @Autowired
    RedisConnectionFactory connectionFactory;

    @Autowired
    JsonMapper json;

    @Autowired
    PushHub hub;

    WebTestClient client() {
        return WebTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @Test
    void repeatedFailedLoginsAreSlowedDown() {
        // successful logins never count
        for (int i = 0; i < 20; i++) {
            client().post().uri("/api/resource/v1/auth/login").header("X-Forwarded-For", "203.0.113.6")
                    .exchange().expectStatus().isOk();
        }
        BACKEND_STATUS.set(401);
        try {
            for (int i = 0; i < 10; i++) {
                client().post().uri("/api/resource/v1/auth/login").header("X-Forwarded-For", "203.0.113.7")
                        .exchange().expectStatus().isUnauthorized();
            }
            client().post().uri("/api/resource/v1/auth/login").header("X-Forwarded-For", "203.0.113.7").exchange()
                    .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                    .expectHeader().exists("Retry-After")
                    .expectBody().jsonPath("$.code").isEqualTo("TOO_MANY_LOGIN_ATTEMPTS");
        } finally {
            BACKEND_STATUS.set(200);
        }
        client().post().uri("/api/resource/v1/auth/login").header("X-Forwarded-For", "203.0.113.8")
                .exchange().expectStatus().isOk();
    }

    @Test
    void loginNeedsNoToken() {
        client().post().uri("/api/resource/v1/auth/login").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/api/resource/v1/auth/login");
    }

    @Test
    void requestsWithoutAValidTokenAreRejected() {
        client().get().uri("/api/fleet/v1/vehicles").exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.code").isEqualTo("UNAUTHORIZED");
        client().get().uri("/api/fleet/v1/vehicles").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("amy", "ADMIN", "another-secret-0123456789abcdef-01234", Duration.ofHours(1)))
                .exchange().expectStatus().isUnauthorized();
        client().get().uri("/api/fleet/v1/vehicles").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("amy", "ADMIN", SECRET, Duration.ofHours(-1)))
                .exchange().expectStatus().isUnauthorized();
    }

    @Test
    void servicesLearnTheCallerFromTheTokenNotFromTheClient() {
        client().get().uri("/api/dispatch/v1/orders?limit=5")
                .header(HttpHeaders.AUTHORIZATION, bearer("viewer1", "VIEWER"))
                .header("X-User-Name", "admin")
                .header("X-User-Role", "ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.method").isEqualTo("GET")
                .jsonPath("$.path").isEqualTo("/api/dispatch/v1/orders")
                .jsonPath("$.user").isEqualTo("viewer1")
                .jsonPath("$.role").isEqualTo("VIEWER");
    }

    @Test
    void viewersOnlyRead() {
        client().post().uri("/api/dispatch/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer("viewer1", "VIEWER"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
        client().put().uri("/api/resource/v1/auth/me/password").header(HttpHeaders.AUTHORIZATION, bearer("viewer1", "VIEWER"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void operatorsChangeThingsButOnlyAdminsManageUsers() {
        client().post().uri("/api/dispatch/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                .exchange().expectStatus().isOk();
        client().get().uri("/api/resource/v1/users").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/api/resource/v1/users").header(HttpHeaders.AUTHORIZATION, bearer("amy", "ADMIN"))
                .exchange().expectStatus().isOk();
        client().get().uri("/api/traffic/v1/settings").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                .exchange().expectStatus().isOk();
        client().put().uri("/api/traffic/v1/settings").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                .exchange().expectStatus().isForbidden();
        client().put().uri("/api/dispatch/v1/settings").header(HttpHeaders.AUTHORIZATION, bearer("amy", "ADMIN"))
                .exchange().expectStatus().isOk();
    }

    @Test
    void unknownPathsAreClosed() {
        client().get().uri("/internal").header(HttpHeaders.AUTHORIZATION, bearer("amy", "ADMIN"))
                .exchange().expectStatus().isForbidden();
        client().get().uri("/actuator/health").exchange().expectStatus().isOk();
        client().post().uri("/api/fleet/v1/vehicles/AGV-1/orders").header(HttpHeaders.AUTHORIZATION, bearer("amy", "ADMIN"))
                .exchange().expectStatus().isForbidden();
        client().post().uri("/api/fleet/v1/vehicles/AGV-1/orders/current/updates")
                .header(HttpHeaders.AUTHORIZATION, bearer("amy", "ADMIN")).exchange().expectStatus().isForbidden();
    }

    @Test
    void pushNeedsAToken() {
        client().get().uri("/ws").exchange().expectStatus().isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void eventsArePushedToConnectedBrowsers() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        URI uri = URI.create("ws://localhost:" + port + "/ws?access_token=" + token("viewer1", "VIEWER", SECRET, Duration.ofHours(1)));
        Disposable connection = new ReactorNettyWebSocketClient()
                .execute(uri, session -> session.receive().map(WebSocketMessage::getPayloadAsText).doOnNext(received::add).then())
                .subscribe();
        try (ValkeyEventBus fleet = new ValkeyEventBus(connectionFactory, json, "fleetloom-fleet", Clock.systemUTC())) {
            await().atMost(Duration.ofSeconds(10)).until(() -> hub.sessionCount() == 1);
            fleet.publish("VehicleConnectionChanged", Map.of("vehicleCode", "AGV-1", "status", "ONLINE"));
            fleet.publish("NotForBrowsers", Map.of("secret", true));

            await().atMost(Duration.ofSeconds(10)).until(() -> !received.isEmpty());
            JsonNode message = json.readTree(received.getFirst());
            assertThat(message.get("type").asString()).isEqualTo("VehicleConnectionChanged");
            assertThat(message.get("occurredAt").asString()).isNotBlank();
            assertThat(message.get("payload").get("vehicleCode").asString()).isEqualTo("AGV-1");
            await().during(Duration.ofMillis(500)).until(() -> received.size() == 1);
        } finally {
            connection.dispose();
        }
        await().atMost(Duration.ofSeconds(10)).until(() -> hub.sessionCount() == 0);
    }

    @Test
    void changesAreAuditedIncludingRefusedOnesButReadsAreNot() throws Exception {
        List<AuditRecordedEvent> audit = new CopyOnWriteArrayList<>();
        try (ValkeyEventBus resource = new ValkeyEventBus(connectionFactory, json, "fleetloom-resource", Clock.systemUTC())) {
            resource.subscribeLive(AuditRecordedEvent.TYPE, "audit-test-" + System.nanoTime(), AuditRecordedEvent.class,
                    e -> audit.add(e.payload()));

            client().get().uri("/api/dispatch/v1/orders").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                    .exchange().expectStatus().isOk();
            client().post().uri("/api/dispatch/v1/orders?dryRun=1").header(HttpHeaders.AUTHORIZATION, bearer("op", "OPERATOR"))
                    .exchange().expectStatus().isOk();
            client().delete().uri("/api/resource/v1/users/bob").header(HttpHeaders.AUTHORIZATION, bearer("viewer1", "VIEWER"))
                    .exchange().expectStatus().isForbidden();
            client().post().uri("/api/fleet/v1/vehicles/AGV-1/pause").exchange().expectStatus().isUnauthorized();

            await().atMost(Duration.ofSeconds(10)).until(() -> audit.size() >= 3);
            await().during(Duration.ofMillis(300)).until(() -> audit.size() == 3);
        }
        assertThat(audit).anySatisfy(a -> {
            assertThat(a.username()).isEqualTo("op");
            assertThat(a.role()).isEqualTo("OPERATOR");
            assertThat(a.method()).isEqualTo("POST");
            assertThat(a.path()).isEqualTo("/api/dispatch/v1/orders");
            assertThat(a.query()).isEqualTo("dryRun=1");
            assertThat(a.status()).isEqualTo(200);
            assertThat(a.clientIp()).isNotBlank();
        });
        assertThat(audit).anySatisfy(a -> {
            assertThat(a.username()).isEqualTo("viewer1");
            assertThat(a.method()).isEqualTo("DELETE");
            assertThat(a.status()).isEqualTo(403);
        });
        assertThat(audit).anySatisfy(a -> {
            assertThat(a.username()).isNull();
            assertThat(a.status()).isEqualTo(401);
        });
    }

    private static String bearer(String user, String role) {
        return "Bearer " + token(user, role, SECRET, Duration.ofHours(1));
    }

    private static String token(String user, String role, String secret, Duration validFor) {
        var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("fleetloom").subject(user).claim("role", role)
                .issuedAt(now.minus(Duration.ofHours(2))).expiresAt(now.plus(validFor))
                .build();
        return NimbusJwtEncoder.withSecretKey(key).build()
                .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private static HttpServer startBackend() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/", exchange -> {
                var h = exchange.getRequestHeaders();
                String body = """
                        {"method":"%s","path":"%s","user":%s,"role":%s}""".formatted(
                        exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                        quoted(h.getFirst("X-User-Name")), quoted(h.getFirst("X-User-Role")));
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(BACKEND_STATUS.get(), bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quoted(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }
}
