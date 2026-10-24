package org.fleetloom.resource.vehicle.domain;

import java.time.Instant;
import java.util.List;

public interface VehicleHistoryRepository {

    void insert(VehicleSample sample);

    /** Oldest first, {@code from <= at < to}, at most {@code limit} samples. */
    List<VehicleSample> find(String vehicleCode, Instant from, Instant to, int limit);

    /** @return how many samples were removed */
    int deleteBefore(Instant cutoff);
}
