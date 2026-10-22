package org.rcs3000.dispatch.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.storage.TransportLockDto;
import org.rcs3000.contracts.traffic.CostMatrixDto;
import org.rcs3000.contracts.traffic.CostQueryDto;
import org.rcs3000.contracts.traffic.RouteViewDto;

/** Outbound ports of the dispatcher. Remote failures surface as {@link RemoteCallException}. */
public final class Ports {

    private Ports() {
    }

    public interface ResourceGateway {

        Optional<CarrierInfo> carrier(String code);

        /** @return map code and access point of a storage location */
        Optional<LocationInfo> location(String code);

        TransportLockDto lock(String owner, String carrierCode, String targetLocation);

        void pickedUp(String owner, String carrierCode, String vehicleCode);

        void droppedOff(String owner, String carrierCode, String targetLocation);

        void release(String owner, List<String> locations);

        /** Empty locations, nearest to {@code nearPoint} first when it is given. */
        List<String> availableLocations(String zone, String carrierType, String mapCode, String nearPoint);

        List<RegisteredVehicle> vehicles();

        /** Carrier type code → vehicle type codes able to carry it (empty set: all). */
        Map<String, Set<String>> carrierCompatibility();

        List<String> chargingPoints(String mapCode);

        /** Points an idle vehicle may wait on: plain, parking and waiting points. */
        List<String> waitingPoints(String mapCode);

        /** Points where idle vehicles are put away (DSP-08). */
        List<String> parkingPoints(String mapCode);
    }

    public interface TrafficGateway {

        RouteViewDto requestRoute(String vehicleCode, String point, List<VehicleActionDto> targetActions);

        void cancelRoute(String vehicleCode);

        Optional<RouteViewDto> route(String vehicleCode);

        CostMatrixDto costs(CostQueryDto query);
    }

    public interface FleetGateway {

        void stopCharging(String vehicleCode);
    }

    public interface OrderRepository {

        void insert(TransportOrder order);

        /** Optimistic update; fails with {@link DispatchErrorCode#ORDER_CONCURRENT_MODIFICATION}. */
        void update(TransportOrder order);

        Optional<TransportOrder> findByNo(String orderNo);

        Optional<TransportOrder> findByExternalNo(String externalNo);

        /** Orders that are not final. */
        List<TransportOrder> findActive();

        /** Newest first. */
        List<TransportOrder> find(OrderStatus status, int limit);
    }

    public interface VehicleSettingsRepository {

        /** Vehicles switched to not accepting orders (VEH-04); all others accept. */
        Set<String> notAccepting();

        void setAccepting(String vehicleCode, boolean accepting);
    }

    public record CarrierInfo(String code, String typeCode, String locationType, String locationRef) {
    }

    public record LocationInfo(String code, String mapCode, String accessPoint) {
    }

    /** A vehicle from the registry in rcs-resource. */
    public record RegisteredVehicle(String code, String typeCode, String manufacturer, String serialNumber,
            boolean enabled, double chargeThresholdLow, double chargeThresholdHigh) {
    }

    public interface TemplateRepository {

        Optional<OrderTemplate> find(String code);

        List<OrderTemplate> findAll();

        void save(OrderTemplate template);

        boolean delete(String code);
    }
}
