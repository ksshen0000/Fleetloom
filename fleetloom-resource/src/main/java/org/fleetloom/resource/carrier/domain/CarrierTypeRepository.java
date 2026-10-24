package org.fleetloom.resource.carrier.domain;

import java.util.List;
import java.util.Optional;

public interface CarrierTypeRepository {

    Optional<CarrierType> findByCode(String code);

    List<CarrierType> findAll();

    CarrierType save(CarrierType type);

    void delete(CarrierType type);
}
