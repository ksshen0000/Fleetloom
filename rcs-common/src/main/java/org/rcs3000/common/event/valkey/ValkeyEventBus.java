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
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamReadRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * Event bus on Valkey (Redis-compatible) Streams. One stream per event type, key
 * {@code rcs:events:<type>}, trimmed to about {@link #MAX_LENGTH} entries. Consumer groups are created
 * on first subscription; events are acknowledged after the handler returns.
 *
 * <p>A subscription outlives connection problems: when Valkey restarts or the connection drops (or
 * the connection factory is stopped and started again, as Spring does with paused test contexts),
 * reading resumes once Valkey can be reached again, and a consumer group lost with the data is
 * created anew.
 */
public final class ValkeyEventBus implements EventBus, AutoCloseable {

    static final long MAX_LENGTH = 10_000;
    private static final Logger log = LoggerFactory.getLogger(ValkeyEventBus.class);
    /** pause after a failed read, so that an unreachable Valkey is not polled in a tight loop */
    static final Duration RETRY_PAUSE = Duration.ofSeconds(1);

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
        StreamReadRequest<String> request = StreamReadRequest.builder(StreamOffset.create(key, ReadOffset.lastConsumed()))
                .consumer(org.springframework.data.redis.connection.stream.Consumer.from(group, consumerName))
                .autoAcknowledge(false)
                .cancelOnError(e -> false)
                .errorHandler(e -> readFailed(key, group, e))
                .build();
        container.register(request, record -> {
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

    /** Runs on the polling thread of the subscription, which carries on reading afterwards. */
    private void readFailed(String key, String group, Throwable e) {
        if (closing) {
            return;
        }
        log.warn("reading events of {} for group {} failed, retrying: {}", key, group, e.getMessage());
        if (hasMessage(e, "NOGROUP")) {
            try {
                createGroupIfAbsent(key, group);
            } catch (RuntimeException again) {
                log.debug("could not create group {} yet: {}", group, again.getMessage());
            }
        }
        try {
            Thread.sleep(RETRY_PAUSE);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
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
        return hasMessage(e, "BUSYGROUP");
    }

    private static boolean hasMessage(Throwable e, String text) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(text)) {
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
