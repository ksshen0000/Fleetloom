package org.rcs3000.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** Event bus against a real Valkey. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class ValkeyEventBusIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    record Moved(String vehicle, int node) {
    }

    private LettuceConnectionFactory connectionFactory;
    private ValkeyEventBus publisher;
    private ValkeyEventBus consumerA;
    private ValkeyEventBus consumerB;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(VALKEY.getHost(), VALKEY.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        JsonMapper json = JsonMapper.builder().build();
        publisher = new ValkeyEventBus(connectionFactory, json, "fleet", Clock.systemUTC());
        consumerA = new ValkeyEventBus(connectionFactory, json, "traffic", Clock.systemUTC());
        consumerB = new ValkeyEventBus(connectionFactory, json, "dispatch", Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        publisher.close();
        consumerA.close();
        consumerB.close();
        connectionFactory.destroy();
    }

    @Test
    void everyGroupReceivesEveryEventWithItsEnvelope() {
        String type = "Moved-" + System.nanoTime();
        List<EventEnvelope<Moved>> traffic = new CopyOnWriteArrayList<>();
        List<EventEnvelope<Moved>> dispatch = new CopyOnWriteArrayList<>();
        consumerA.subscribe(type, "traffic", Moved.class, traffic::add);
        consumerB.subscribe(type, "dispatch", Moved.class, dispatch::add);

        publisher.publish(type, new Moved("AGV-1", 2));
        publisher.publish(type, new Moved("AGV-1", 4));

        await().atMost(Duration.ofSeconds(5)).until(() -> traffic.size() == 2 && dispatch.size() == 2);
        assertThat(traffic).extracting(EventEnvelope::payload).containsExactly(new Moved("AGV-1", 2), new Moved("AGV-1", 4));
        assertThat(traffic.getFirst().source()).isEqualTo("fleet");
        assertThat(traffic.getFirst().eventId()).isEqualTo(dispatch.getFirst().eventId());
    }

    @Test
    void instancesOfOneGroupShareTheEvents() {
        String type = "Moved-" + System.nanoTime();
        List<Moved> received = new CopyOnWriteArrayList<>();
        consumerA.subscribe(type, "traffic", Moved.class, e -> received.add(e.payload()));
        consumerB.subscribe(type, "traffic", Moved.class, e -> received.add(e.payload()));

        for (int i = 0; i < 10; i++) {
            publisher.publish(type, new Moved("AGV-1", i));
        }

        await().atMost(Duration.ofSeconds(5)).until(() -> received.size() >= 10);
        await().during(Duration.ofMillis(500)).until(() -> received.size() == 10);
    }

    @Test
    void liveSubscriptionSkipsWhatWasPublishedWhileAway() {
        String type = "Moved-" + System.nanoTime();
        String key = ValkeyEventBus.streamKey(type);
        List<Moved> received = new CopyOnWriteArrayList<>();
        // the group exists from an earlier run and then misses two events, as if its service had been down
        new StringRedisTemplate(connectionFactory).execute((org.springframework.data.redis.core.RedisCallback<Object>) c ->
                c.streamCommands().xGroupCreate(key.getBytes(), "push",
                        org.springframework.data.redis.connection.stream.ReadOffset.latest(), true));
        publisher.publish(type, new Moved("AGV-1", 1));
        publisher.publish(type, new Moved("AGV-1", 2));

        consumerB.subscribeLive(type, "push", Moved.class, e -> received.add(e.payload()));
        publisher.publish(type, new Moved("AGV-1", 3));

        await().atMost(Duration.ofSeconds(5)).until(() -> !received.isEmpty());
        await().during(Duration.ofMillis(500)).until(() -> received.equals(List.of(new Moved("AGV-1", 3))));
    }

    @Test
    void subscriptionCarriesOnAfterTheConnectionWasLost() {
        String type = "Moved-" + System.nanoTime();
        List<Moved> received = new CopyOnWriteArrayList<>();
        consumerA.subscribe(type, "traffic", Moved.class, e -> received.add(e.payload()));

        // what Spring does with a paused test context, and much like Valkey going away for a moment
        connectionFactory.stop();
        connectionFactory.start();
        publisher.publish(type, new Moved("AGV-1", 1));

        await().atMost(Duration.ofSeconds(10)).until(() -> received.equals(List.of(new Moved("AGV-1", 1))));
    }

    @Test
    void consumerGroupLostWithTheDataIsCreatedAgain() {
        String type = "Moved-" + System.nanoTime();
        List<Moved> received = new CopyOnWriteArrayList<>();
        consumerA.subscribe(type, "traffic", Moved.class, e -> received.add(e.payload()));
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);

        // e.g. Valkey restarted without persistence
        redis.delete(ValkeyEventBus.streamKey(type));
        await().atMost(Duration.ofSeconds(10)).until(() ->
                Boolean.TRUE.equals(redis.hasKey(ValkeyEventBus.streamKey(type))));
        publisher.publish(type, new Moved("AGV-1", 2));

        await().atMost(Duration.ofSeconds(10)).until(() -> received.equals(List.of(new Moved("AGV-1", 2))));
    }

    @Test
    void failedHandlerLeavesTheEventPending() {
        String type = "Moved-" + System.nanoTime();
        List<Moved> attempts = new CopyOnWriteArrayList<>();
        consumerA.subscribe(type, "traffic", Moved.class, e -> {
            attempts.add(e.payload());
            throw new IllegalStateException("boom");
        });

        publisher.publish(type, new Moved("AGV-1", 1));

        await().atMost(Duration.ofSeconds(5)).until(() -> attempts.size() == 1);
        StringRedisTemplate redis = new StringRedisTemplate(connectionFactory);
        await().atMost(Duration.ofSeconds(2)).until(() ->
                redis.opsForStream().pending(ValkeyEventBus.streamKey(type), "traffic").getTotalPendingMessages() == 1);
    }
}
