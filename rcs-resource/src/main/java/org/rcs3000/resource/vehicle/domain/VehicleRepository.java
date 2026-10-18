package org.rcs3000.resource.vehicle.domain;

import java.util.List;
import java.util.Optional;

public interface VehicleRepository {

    Optional<Vehicle> findByCode(String code);

    Optional<Vehicle> findByIdentity(String manufacturer, String serialNumber);

    List<Vehicle> findAll();

    boolean existsOfType(String typeCode);

    Vehicle save(Vehicle vehicle);

    void delete(Vehicle vehicle);
}
