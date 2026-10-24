package org.fleetloom.resource.vehicle.domain;

import java.util.List;
import java.util.Optional;

public interface VehicleTypeRepository {

    Optional<VehicleType> findByCode(String code);

    List<VehicleType> findAll();

    VehicleType save(VehicleType type);

    void delete(VehicleType type);
}
