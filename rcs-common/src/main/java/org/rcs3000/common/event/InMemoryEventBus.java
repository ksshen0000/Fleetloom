package org.rcs3000.common.event;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Synchronous, process-local event bus for tests and single-process setups. */
public final class InMemoryEventBus implements EventBus {

    private static final Logger log = LoggerFactory.getLogger(InMemoryEventBus.class);

    private final String source;
    private final Clock clock;
    private final List<Subscription<?>> subscriptions = new CopyOnWriteArrayList<>();

    public InMemoryEventBus(String source, Clock clock) {
        this.source = source;
        this.clock = clock;
    }

    @Override
    public void publish(String type, Object payload) {
        String id = UUID.randomUUID().toString();
        for (Subscription<?> s : subscriptions) {
            if (s.type.equals(type)) {
                s.deliver(id, clock, source, payload);
            }
        }
    }

    @Override
    public <T> void subscribe(String type, String group, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {
        subscriptions.add(new Subscription<>(type, payloadType, handler));
    }

    private record Subscription<T>(String type, Class<T> payloadType, Consumer<EventEnvelope<T>> handler) {

        void deliver(String id, Clock clock, String source, Object payload) {
            try {
                handler.accept(new EventEnvelope<>(id, type, clock.instant(), source, payloadType.cast(payload)));
            } catch (RuntimeException e) {
                log.error("handler for {} failed", type, e);
            }
        }
    }
}
