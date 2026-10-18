package org.rcs3000.resource.carrier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.carrier.application.CarrierViews.CarrierView;
import org.rcs3000.contracts.storage.TransportLockDto;
import org.rcs3000.resource.carrier.domain.Carrier;
import org.rcs3000.resource.carrier.domain.CarrierErrorCode;
import org.rcs3000.resource.carrier.domain.CarrierLocationType;
import org.rcs3000.resource.carrier.domain.CarrierMovement;
import org.rcs3000.resource.carrier.domain.CarrierRepository;
import org.rcs3000.resource.storage.application.StorageViews.LocationView;
import org.rcs3000.resource.storage.domain.StorageErrorCode;
import org.rcs3000.resource.storage.domain.StorageLocation;
import org.rcs3000.resource.storage.domain.StorageLocationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The storage side of a transport order, called by the dispatch service: lock both ends together,
 * record the pick-up and the drop-off, or release the locks when the order ends otherwise
 * (requirements STO-04, STO-05, CAR-03, CAR-07). Every operation is one transaction.
 */
@Service
@Transactional
public class TransportService {

    private final CarrierRepository carriers;
    private final StorageLocationRepository locations;
    private final Clock clock;

    public TransportService(CarrierRepository carriers, StorageLocationRepository locations, Clock clock) {
        this.carriers = carriers;
        this.locations = locations;
        this.clock = clock;
    }

    /**
     * Locks the carrier's current location for outbound and the target for inbound, both or neither.
     *
     * @param owner          the transport order; only it may complete or release the locks
     * @param carrierCode    the carrier to move; its current location is the source
     * @param targetLocation where it goes
     */
    public TransportLockDto lock(String owner, String carrierCode, String targetLocation) {
        Carrier carrier = requireCarrier(carrierCode);
        carrier.requireUsable();
        if (carrier.getLocationType() != CarrierLocationType.STORAGE) {
            throw new BizException(CarrierErrorCode.CARRIER_STATE_INVALID,
                    "carrier %s is not in a storage location".formatted(carrierCode));
        }
        StorageLocation source = requireLocation(carrier.getLocationRef());
        StorageLocation target = requireLocation(targetLocation);
        if (source.getCode().equals(target.getCode())) {
            throw new BizException(StorageErrorCode.LOCATION_STATE_INVALID,
                    "carrier %s is already in %s".formatted(carrierCode, targetLocation));
        }
        Instant now = clock.instant();
        source.lockOutbound(owner, now);
        target.lockInbound(owner, carrier.getTypeCode(), now);
        locations.update(source);
        locations.update(target);
        return new TransportLockDto(owner, carrierCode, carrier.getTypeCode(), source.getCode(), source.getMapCode(),
                source.getAccessPoint(), target.getCode(), target.getMapCode(), target.getAccessPoint());
    }

    /** The vehicle has lifted the carrier: the source becomes empty, the carrier is on the vehicle. */
    public CarrierView pickedUp(String owner, String carrierCode, String vehicleCode) {
        Carrier carrier = requireCarrier(carrierCode);
        String from = carrier.getLocationRef();
        if (carrier.getLocationType() != CarrierLocationType.STORAGE) {
            throw new BizException(CarrierErrorCode.CARRIER_STATE_INVALID,
                    "carrier %s is not in a storage location".formatted(carrierCode));
        }
        StorageLocation source = requireLocation(from);
        Instant now = clock.instant();
        source.completeOutbound(owner, now);
        carrier.loadOnto(vehicleCode, now);
        locations.update(source);
        carriers.update(carrier);
        carriers.record(new CarrierMovement(carrierCode, CarrierLocationType.STORAGE, from, CarrierLocationType.VEHICLE,
                vehicleCode, owner, now));
        return CarrierView.of(carrier);
    }

    /** The vehicle has set the carrier down in the target location. */
    public CarrierView droppedOff(String owner, String carrierCode, String targetLocation) {
        Carrier carrier = requireCarrier(carrierCode);
        String vehicle = carrier.getLocationRef();
        StorageLocation target = requireLocation(targetLocation);
        Instant now = clock.instant();
        target.completeInbound(owner, carrierCode, now);
        carrier.unloadInto(targetLocation, now);
        locations.update(target);
        carriers.update(carrier);
        carriers.record(new CarrierMovement(carrierCode, CarrierLocationType.VEHICLE, vehicle, CarrierLocationType.STORAGE,
                targetLocation, owner, now));
        return CarrierView.of(carrier);
    }

    /**
     * Releases the locks {@code owner} holds on the given locations, e.g. when its order is cancelled.
     * Locations not locked by {@code owner} are left alone.
     */
    public List<LocationView> release(String owner, List<String> locationCodes) {
        List<LocationView> released = new ArrayList<>();
        Instant now = clock.instant();
        for (String code : locationCodes) {
            StorageLocation location = requireLocation(code);
            if (location.getStatus().isLocked() && owner.equals(location.getLockOwner())) {
                location.unlock(owner, now);
                locations.update(location);
                released.add(LocationView.of(location));
            }
        }
        return released;
    }

    private Carrier requireCarrier(String code) {
        return carriers.findByCode(code).orElseThrow(() ->
                new BizException(CarrierErrorCode.CARRIER_NOT_FOUND, "carrier %s not found".formatted(code)));
    }

    private StorageLocation requireLocation(String code) {
        return locations.findByCode(code).orElseThrow(() ->
                new BizException(StorageErrorCode.LOCATION_NOT_FOUND, "location %s not found".formatted(code)));
    }
}
