package org.rcs3000.common.event;

import java.util.function.Consumer;

/**
 * Asynchronous inter-service events (docs/requirements.md section 3.4). Each event type is its own
 * stream. Every consumer group receives every event once; within a group, events are shared among
 * the group's instances. Delivery is at-least-once, so handlers must be idempotent.
 */
public interface EventBus {

    void publish(String type, Object payload);

    /**
     * Subscribes {@code group} to events of {@code type}. Events published before the group first
     * subscribed are not delivered. A handler that throws leaves the event unacknowledged.
     */
    <T> void subscribe(String type, String group, Class<T> payloadType, Consumer<EventEnvelope<T>> handler);
}
