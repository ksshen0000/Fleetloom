package org.fleetloom.resource.system.domain;

import java.util.List;
import java.util.Optional;

public interface AlarmRepository {

    Optional<Alarm> findById(long id);

    /** All alarms that are not cleared. */
    List<Alarm> findOpen();

    /** Newest first; {@code open == null} means both open and cleared. */
    List<Alarm> find(Boolean open, AlarmLevel level, String vehicleCode, int limit);

    Alarm save(Alarm alarm);
}
