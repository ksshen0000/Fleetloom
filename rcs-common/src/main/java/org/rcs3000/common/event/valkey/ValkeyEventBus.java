package org.rcs3000.common.event.valkey;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.common.event.EventEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStreamCommands.XAddOptions;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event bus on Valkey (Redis-compatible) Streams. One stream per event type, key
 * {@code rcs:events:<type>}, trimmed to about {@link #MAX_LENGTH} entries. Consumer groups are created
 * on first subscription; events are acknowledged after the handler returns.
 */
public final class ValkeyEventBus implements EventBus, AutoCloseable {

    static final long MAX_LENGTH = 10_000;
    private static final Logger log = LoggerFactory.getLogger(ValkeyEventBus.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final String source;
    private final String consumerName;
    private final Clock clock;
    private final StreamMessageListenerContainer<String, MapRecord<String, String, String>> container;
    private volatile boolean closing;

    public ValkeyEventBus(RedisConnectionFactory connectionFactory, JsonMapper json, String source, Clock clock) {
        this.redis = new StringRedisTemplate(connectionFactory);
        this.json = json;
        this.source = source;
        this.clock = clock;
        this.consumerName = source + "-" + ProcessHandle.current().pid() + "-" + UUID.randomUUID().toString().substring(0, 8);
        this.container = StreamMessageListenerContainer.create(connectionFactory,
                StreamMessageListenerContainerOptions.builder()
                        .pollTimeout(Duration.ofMillis(200))
                        .errorHandler(e -> {
                            if (!closing) {
                                log.warn("event stream polling failed: {}", e.getMessage());
                            }
                        })
                        .build());
        this.container.start();
    }

    public static String streamKey(String type) {
        return "rcs:events:" + type;
    }

    @Override
    public void publish(String type, Object payload) {
        Map<String, String> fields = Map.of(
                "eventId", UUID.randomUUID().toString(),
                "type", type,
                "occurredAt", clock.instant().toString(),
                "source", source,
                "payload", json.writeValueAsString(payload));
        redis.opsForStream().add(StreamRecords.string(fields).withStreamKey(streamKey(type)),
                XAddOptions.maxlen(MAX_LENGTH).approximateTrimming(true));
    }

    @Override
    public <T> void subscribe(String type, String group, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {
        String key = streamKey(type);
        createGroupIfAbsent(key, group);
        listen(key, type, group, payloadType, handler);
    }

    @Override
    public <T> void subscribeLive(String type, String group, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {
        String key = streamKey(type);
        createGroupIfAbsent(key, group);
        // skip whatever the group has not read yet, e.g. from before a restart
        redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
            connection.execute("XGROUP", "SETID".getBytes(), key.getBytes(), group.getBytes(), "$".getBytes());
            return null;
        });
        listen(key, type, group, payloadType, handler);
    }

    private <T> void listen(String key, String type, String group, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {
        container.receive(org.springframework.data.redis.connection.stream.Consumer.from(group, consumerName),
                StreamOffset.create(key, ReadOffset.lastConsumed()),
                record -> {
                    Map<String, String> f = record.getValue();
                    try {
                        handler.accept(new EventEnvelope<>(f.get("eventId"), f.get("type"),
                                Instant.parse(f.get("occurredAt")), f.get("source"),
                                json.readValue(f.get("payload"), payloadType)));
                        redis.opsForStream().acknowledge(key, group, record.getId());
                    } catch (RuntimeException e) {
                        log.error("handler for {} in group {} failed; event {} stays pending", type, group,
                                f.get("eventId"), e);
                    }
                });
    }

    private void createGroupIfAbsent(String key, String group) {
        try {
            redis.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection ->
                    connection.streamCommands().xGroupCreate(key.getBytes(), group, ReadOffset.latest(), true));
        } catch (RedisSystemException e) {
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    /** XGROUP CREATE fails with BUSYGROUP when the group already exists. */
    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        closing = true;
        container.stop();
    }
}
