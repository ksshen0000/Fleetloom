package org.fleetloom.resource.vehicle.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.vehicle.domain.Vehicle;
import org.fleetloom.resource.vehicle.domain.VehicleCategory;
import org.fleetloom.resource.vehicle.domain.VehicleErrorCode;
import org.fleetloom.resource.vehicle.domain.VehicleRepository;
import org.fleetloom.resource.vehicle.domain.VehicleType;
import org.fleetloom.resource.vehicle.domain.VehicleTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Vehicle types and the vehicle registry (requirements VEH-01, VEH-02). */
@Service
@Transactional
public class VehicleRegistryService {

    private final VehicleTypeRepository types;
    private final VehicleRepository vehicles;
    private final Clock clock;

    public VehicleRegistryService(VehicleTypeRepository types, VehicleRepository vehicles, Clock clock) {
        this.types = types;
        this.vehicles = vehicles;
        this.clock = clock;
    }

    public VehicleType createType(String code, String name, VehicleCategory category, double length, double width,
            double maxSpeed, double maxLoad, Set<String> supportedActions) {
        if (types.findByCode(code).isPresent()) {
            throw new BizException(VehicleErrorCode.VEHICLE_TYPE_CODE_DUPLICATE, "vehicle type %s already exists".formatted(code));
        }
        Instant now = clock.instant();
        return types.save(new VehicleType(null, code, name, category, length, width, maxSpeed, maxLoad,
                supportedActions == null ? Set.of() : supportedActions, now, now));
    }

    public VehicleType updateType(String code, String name, VehicleCategory category, double length, double width,
            double maxSpeed, double maxLoad, Set<String> supportedActions) {
        VehicleType old = requireType(code);
        return types.save(new VehicleType(old.id(), code, name, category, length, width, maxSpeed, maxLoad,
                supportedActions == null ? Set.of() : supportedActions, old.createdAt(), clock.instant()));
    }

    public void deleteType(String code) {
        VehicleType type = requireType(code);
        if (vehicles.existsOfType(code)) {
            throw new BizException(VehicleErrorCode.VEHICLE_TYPE_IN_USE, "vehicles of type %s still exist".formatted(code));
        }
        types.delete(type);
    }

    @Transactional(readOnly = true)
    public List<VehicleType> listTypes() {
        return types.findAll();
    }

    public Vehicle register(String code, String name, String typeCode, String manufacturer, String serialNumber,
            boolean simulated, String initialPoint, double chargeThresholdLow, double chargeThresholdHigh) {
        if (vehicles.findByCode(code).isPresent()) {
            throw new BizException(VehicleErrorCode.VEHICLE_CODE_DUPLICATE, "vehicle %s already exists".formatted(code));
        }
        requireType(typeCode);
        requireUniqueIdentity(null, manufacturer, serialNumber);
        Instant now = clock.instant();
        return vehicles.save(new Vehicle(null, code, name, typeCode, manufacturer, serialNumber, simulated, true,
                initialPoint, chargeThresholdLow, chargeThresholdHigh, now, now));
    }

    public Vehicle update(String code, String name, String typeCode, String manufacturer, String serialNumber,
            boolean simulated, boolean enabled, String initialPoint, double chargeThresholdLow, double chargeThresholdHigh) {
        Vehicle old = require(code);
        requireType(typeCode);
        requireUniqueIdentity(code, manufacturer, serialNumber);
        return vehicles.save(new Vehicle(old.id(), code, name, typeCode, manufacturer, serialNumber, simulated, enabled,
                initialPoint, chargeThresholdLow, chargeThresholdHigh, old.createdAt(), clock.instant()));
    }

    public void delete(String code) {
        vehicles.delete(require(code));
    }

    @Transactional(readOnly = true)
    public Vehicle get(String code) {
        return require(code);
    }

    @Transactional(readOnly = true)
    public List<Vehicle> list() {
        return vehicles.findAll();
    }

    private void requireUniqueIdentity(String ownCode, String manufacturer, String serialNumber) {
        vehicles.findByIdentity(manufacturer, serialNumber)
                .filter(v -> !v.code().equals(ownCode))
                .ifPresent(v -> {
                    throw new BizException(VehicleErrorCode.VEHICLE_IDENTITY_DUPLICATE,
                            "%s/%s is already registered as %s".formatted(manufacturer, serialNumber, v.code()));
                });
    }

    private VehicleType requireType(String code) {
        return types.findByCode(code).orElseThrow(() ->
                new BizException(VehicleErrorCode.VEHICLE_TYPE_NOT_FOUND, "vehicle type %s not found".formatted(code)));
    }

    private Vehicle require(String code) {
        return vehicles.findByCode(code).orElseThrow(() ->
                new BizException(VehicleErrorCode.VEHICLE_NOT_FOUND, "vehicle %s not found".formatted(code)));
    }
}
