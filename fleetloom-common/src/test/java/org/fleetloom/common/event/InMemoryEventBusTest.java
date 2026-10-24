package org.fleetloom.common.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryEventBusTest {

    private final InMemoryEventBus bus =
            new InMemoryEventBus("test", Clock.fixed(Instant.parse("2026-10-18T00:00:00Z"), ZoneOffset.UTC));

    @Test
    void deliversToSubscribersOfTheTypeOnly() {
        List<EventEnvelope<String>> a = new ArrayList<>();
        List<EventEnvelope<String>> b = new ArrayList<>();
        bus.subscribe("A", "g", String.class, a::add);
        bus.subscribe("B", "g", String.class, b::add);

        bus.publish("A", "hello");

        assertThat(a).singleElement().satisfies(e -> {
            assertThat(e.payload()).isEqualTo("hello");
            assertThat(e.source()).isEqualTo("test");
            assertThat(e.occurredAt()).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        });
        assertThat(b).isEmpty();
    }

    @Test
    void failingHandlerDoesNotAffectOthers() {
        List<String> received = new ArrayList<>();
        bus.subscribe("A", "g1", String.class, e -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribe("A", "g2", String.class, e -> received.add(e.payload()));

        bus.publish("A", "x");

        assertThat(received).containsExactly("x");
    }
}
