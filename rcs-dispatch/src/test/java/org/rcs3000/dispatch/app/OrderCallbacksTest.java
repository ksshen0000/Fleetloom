package org.rcs3000.dispatch.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.event.InMemoryEventBus;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderStatusChangedEvent;
import org.rcs3000.contracts.dispatch.OrderType;
import org.rcs3000.contracts.dispatch.OrderViewDto;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OrderCallbacksTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final InMemoryEventBus events = new InMemoryEventBus("rcs-dispatch", Clock.systemUTC());
    private final List<Received> received = new ArrayList<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private HttpServer upstream;

    record Received(String eventId, String signature, JsonNode body) {
    }

    @AfterEach
    void stop() {
        if (upstream != null) {
            upstream.stop(0);
        }
    }

    @Test
    void everyStatusChangeIsPostedWithIdAndSignature() throws IOException {
        start(new CallbackProperties(url(), "s3cret", 3, Duration.ofMillis(10), Duration.ofSeconds(2)));

        publish("T-1", OrderStatus.EXECUTING, OrderStatus.QUEUED);
        publish("T-1", OrderStatus.COMPLETED, OrderStatus.EXECUTING);

        assertThat(received).hasSize(2);
        Received first = received.getFirst();
        assertThat(first.body().get("event").asString()).isEqualTo("TransportOrderStatusChanged");
        assertThat(first.body().get("previous").asString()).isEqualTo("QUEUED");
        assertThat(first.body().get("order").get("orderNo").asString()).isEqualTo("T-1");
        assertThat(first.body().get("order").get("status").asString()).isEqualTo("EXECUTING");
        assertThat(first.eventId()).isEqualTo(first.body().get("eventId").asString()).isNotBlank();
        assertThat(first.signature()).startsWith("sha256=").hasSize(7 + 64);
        assertThat(received.get(1).body().get("order").get("status").asString()).isEqualTo("COMPLETED");
    }

    @Test
    void failedCallsAreRetriedThenDropped() throws IOException {
        start(new CallbackProperties(url(), "", 2, Duration.ofMillis(10), Duration.ofSeconds(2)));

        failuresLeft.set(2);
        publish("T-1", OrderStatus.EXECUTING, OrderStatus.QUEUED);
        assertThat(received).hasSize(1);
        assertThat(received.getFirst().signature()).isNull();

        failuresLeft.set(10);
        publish("T-2", OrderStatus.EXECUTING, OrderStatus.QUEUED);
        failuresLeft.set(0);
        publish("T-3", OrderStatus.EXECUTING, OrderStatus.QUEUED);
        // T-2 was given up after 3 attempts; later changes still go out
        assertThat(received).extracting(r -> r.body().get("order").get("orderNo").asString()).containsExactly("T-1", "T-3");
    }

    @Test
    void nothingIsSentWithoutAUrl() throws IOException {
        upstream();
        new OrderCallbacks(new CallbackProperties("", "", 3, Duration.ofMillis(10), Duration.ofSeconds(2)), json,
                RestClient.builder()).start(events);
        publish("T-1", OrderStatus.EXECUTING, OrderStatus.QUEUED);
        assertThat(received).isEmpty();
    }

    private void start(CallbackProperties properties) {
        new OrderCallbacks(properties, json, RestClient.builder()).start(events);
    }

    private String url() throws IOException {
        upstream();
        return "http://localhost:" + upstream.getAddress().getPort() + "/wms/agv-callback";
    }

    private void upstream() throws IOException {
        upstream = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        upstream.createContext("/wms/agv-callback", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (failuresLeft.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.sendResponseHeaders(503, -1);
            } else {
                received.add(new Received(exchange.getRequestHeaders().getFirst("X-RCS-Event-Id"),
                        exchange.getRequestHeaders().getFirst("X-RCS-Signature"),
                        json.readTree(new String(body, StandardCharsets.UTF_8))));
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        upstream.start();
    }

    private void publish(String orderNo, OrderStatus status, OrderStatus previous) {
        Instant now = Instant.now();
        events.publish(OrderStatusChangedEvent.TYPE, new OrderStatusChangedEvent(new OrderViewDto(orderNo, null,
                OrderType.MOVE, 5, status, null, null, null, "DEMO", "AGV-1", 0, List.of(), null, now, now, null, null), previous));
    }
}
