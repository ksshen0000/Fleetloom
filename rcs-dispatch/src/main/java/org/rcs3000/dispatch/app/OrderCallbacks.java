package org.rcs3000.dispatch.app;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.common.event.EventEnvelope;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderStatusChangedEvent;
import org.rcs3000.contracts.dispatch.OrderViewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the upstream system (WMS / MES) about every order status change (DSP-11): one POST per change,
 * in order, retried with exponential backoff. Consumed through a durable consumer group, so changes
 * made while this service was down are still sent after a restart. The receiver should use
 * {@code X-RCS-Event-Id} to drop duplicates.
 */
public final class OrderCallbacks {

    static final String GROUP = "rcs-dispatch-callback";
    private static final Logger log = LoggerFactory.getLogger(OrderCallbacks.class);

    private final CallbackProperties properties;
    private final JsonMapper json;
    private final RestClient rest;

    public OrderCallbacks(CallbackProperties properties, JsonMapper json, RestClient.Builder rest) {
        this.properties = properties;
        this.json = json;
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.timeout());
        requests.setReadTimeout(properties.timeout());
        this.rest = rest.clone().requestFactory(requests).build();
    }

    /** What the upstream system receives. */
    public record Callback(String event, String eventId, Instant occurredAt, OrderStatus previous, OrderViewDto order) {
    }

    public void start(EventBus events) {
        if (!properties.enabled()) {
            return;
        }
        events.subscribe(OrderStatusChangedEvent.TYPE, GROUP, OrderStatusChangedEvent.class, this::send);
        log.info("order status callbacks go to {}", properties.url());
    }

    void send(EventEnvelope<OrderStatusChangedEvent> event) {
        byte[] body = json.writeValueAsBytes(new Callback(event.type(), event.eventId(), event.occurredAt(),
                event.payload().previous(), event.payload().order()));
        long backoff = properties.initialBackoff().toMillis();
        for (int attempt = 0; ; attempt++) {
            try {
                RestClient.RequestBodySpec request = rest.post().uri(properties.url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-RCS-Event-Id", event.eventId());
                if (!properties.secret().isBlank()) {
                    request.header("X-RCS-Signature", "sha256=" + sign(body));
                }
                request.body(body).retrieve().toBodilessEntity();
                return;
            } catch (RuntimeException e) {
                if (attempt >= properties.retries()) {
                    // giving up rather than blocking every later change behind this one
                    log.error("callback for order {} ({}) failed {} times, dropped: {}", event.payload().order().orderNo(),
                            event.payload().order().status(), attempt + 1, e.getMessage());
                    return;
                }
                log.warn("callback for order {} failed, retrying in {} ms: {}", event.payload().order().orderNo(),
                        backoff, e.getMessage());
                sleep(backoff);
                backoff *= 2;
            }
        }
    }

    String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(properties.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
