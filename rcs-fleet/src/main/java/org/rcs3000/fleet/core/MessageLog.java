package org.rcs3000.fleet.core;

import java.time.Instant;
import java.util.List;

/** Outbound port: the VDA 5050 messages exchanged with each vehicle, kept for troubleshooting (FLT-07). */
public interface MessageLog {

    void append(String vehicleCode, Direction direction, String topic, String payload);

    /** Newest first, at most {@code limit} entries with {@code from <= at <= to}; null bounds are open. */
    List<Entry> find(String vehicleCode, Instant from, Instant to, int limit);

    enum Direction {
        /** vehicle to fleet control */
        IN,
        /** fleet control to vehicle */
        OUT
    }

    record Entry(String id, Instant at, String vehicleCode, Direction direction, String topic, String payload) {
    }

    MessageLog NONE = new MessageLog() {
        @Override
        public void append(String vehicleCode, Direction direction, String topic, String payload) {
        }

        @Override
        public List<Entry> find(String vehicleCode, Instant from, Instant to, int limit) {
            return List.of();
        }
    };
}
