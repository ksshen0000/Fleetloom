package org.rcs3000.resource.vehicle.domain;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Decides which reported states go into the history: vehicles report about once a second, which is
 * far more than a trajectory or a battery curve needs. A sample is kept when the vehicle moved at
 * least {@code minDistance} (at most one per {@code minInterval}), when its node, connection or
 * charging changed, when the battery changed by a whole percent, or when {@code maxInterval} has
 * passed. Not thread-safe.
 */
public class HistorySampler {

    private final double minDistance;
    private final Duration minInterval;
    private final Duration maxInterval;
    private final Map<String, VehicleSample> kept = new HashMap<>();

    public HistorySampler(double minDistance, Duration minInterval, Duration maxInterval) {
        this.minDistance = minDistance;
        this.minInterval = minInterval;
        this.maxInterval = maxInterval;
    }

    public boolean accept(VehicleSample s) {
        VehicleSample last = kept.get(s.vehicleCode());
        if (last == null || shouldKeep(last, s)) {
            kept.put(s.vehicleCode(), s);
            return true;
        }
        return false;
    }

    private boolean shouldKeep(VehicleSample last, VehicleSample s) {
        Duration since = Duration.between(last.at(), s.at());
        if (since.isNegative()) {
            return false;
        }
        if (!Objects.equals(last.lastNodeId(), s.lastNodeId()) || !Objects.equals(last.connection(), s.connection())
                || !Objects.equals(last.mapCode(), s.mapCode()) || last.charging() != s.charging()
                || last.driving() != s.driving()) {
            return true;
        }
        if (since.compareTo(maxInterval) >= 0 || Math.abs(last.battery() - s.battery()) >= 1) {
            return true;
        }
        return since.compareTo(minInterval) >= 0 && Math.hypot(s.x() - last.x(), s.y() - last.y()) >= minDistance;
    }
}
