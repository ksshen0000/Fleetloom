package org.fleetloom.common.event;

import java.time.Instant;

/**
 * An event as delivered to subscribers.
 *
 * @param eventId    unique id; consumers use it to drop redeliveries
 * @param type       event type, e.g. {@code VehicleStateUpdated}; also selects the stream
 * @param source     service that published the event
 */
public record EventEnvelope<T>(String eventId, String type, Instant occurredAt, String source, T payload) {
}
