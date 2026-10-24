package org.fleetloom.resource.carrier.domain;

import java.util.List;
import java.util.Optional;

public interface CarrierRepository {

    Optional<Carrier> findByCode(String code);

    List<Carrier> findAll();

    boolean existsOfType(String typeCode);

    void insert(Carrier carrier);

    /** Optimistic update; fails with {@link CarrierErrorCode#CARRIER_CONCURRENT_MODIFICATION}. */
    void update(Carrier carrier);

    void delete(Carrier carrier);

    void record(CarrierMovement movement);

    List<CarrierMovement> movements(String carrierCode, int limit);
}
