package org.rcs3000.dispatch.infra;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.map.PointType;
import org.rcs3000.contracts.storage.DroppedOffDto;
import org.rcs3000.contracts.storage.PickedUpDto;
import org.rcs3000.contracts.storage.ReleaseLocksDto;
import org.rcs3000.contracts.storage.TransportLockDto;
import org.rcs3000.contracts.storage.TransportLockRequestDto;
import org.rcs3000.contracts.traffic.CostMatrixDto;
import org.rcs3000.contracts.traffic.CostQueryDto;
import org.rcs3000.contracts.traffic.RouteRequestDto;
import org.rcs3000.contracts.traffic.RouteViewDto;
import org.rcs3000.dispatch.core.Ports;
import org.rcs3000.dispatch.core.RemoteCallException;

/** HTTP implementations of the dispatcher's outbound ports. */
final class HttpGateways {

    private HttpGateways() {
    }

    static Ports.ResourceGateway resource(HttpApis.ResourceApi api) {
        return new Ports.ResourceGateway() {
            @Override
            public Optional<Ports.CarrierInfo> carrier(String code) {
                return optional(() -> {
                    HttpApis.Carrier c = RemoteCalls.call("rcs-resource", () -> api.carrier(code)).data();
                    return new Ports.CarrierInfo(c.code(), c.typeCode(), c.locationType(), c.locationRef());
                });
            }

            @Override
            public Optional<Ports.LocationInfo> location(String code) {
                return optional(() -> {
                    HttpApis.Location l = RemoteCalls.call("rcs-resource", () -> api.location(code)).data();
                    return new Ports.LocationInfo(l.code(), l.mapCode(), l.accessPoint());
                });
            }

            @Override
            public TransportLockDto lock(String owner, String carrierCode, String targetLocation) {
                return RemoteCalls.call("rcs-resource",
                        () -> api.lock(new TransportLockRequestDto(owner, carrierCode, targetLocation))).data();
            }

            @Override
            public void pickedUp(String owner, String carrierCode, String vehicleCode) {
                RemoteCalls.call("rcs-resource", () -> api.pickedUp(new PickedUpDto(owner, carrierCode, vehicleCode)));
            }

            @Override
            public void droppedOff(String owner, String carrierCode, String targetLocation) {
                RemoteCalls.call("rcs-resource", () -> api.droppedOff(new DroppedOffDto(owner, carrierCode, targetLocation)));
            }

            @Override
            public void release(String owner, List<String> locations) {
                RemoteCalls.call("rcs-resource", () -> api.release(new ReleaseLocksDto(owner, locations)));
            }

            @Override
            public List<String> availableLocations(String zone, String carrierType, String mapCode, String nearPoint) {
                return RemoteCalls.call("rcs-resource", () -> api.available(zone, carrierType, mapCode, nearPoint))
                        .data().stream().map(HttpApis.Location::code).toList();
            }

            @Override
            public List<Ports.RegisteredVehicle> vehicles() {
                return RemoteCalls.call("rcs-resource", api::vehicles).data().stream()
                        .map(v -> new Ports.RegisteredVehicle(v.code(), v.typeCode(), v.manufacturer(), v.serialNumber(),
                                v.enabled(), v.chargeThresholdLow(), v.chargeThresholdHigh()))
                        .toList();
            }

            @Override
            public Map<String, Set<String>> carrierCompatibility() {
                return RemoteCalls.call("rcs-resource", api::carrierTypes).data().stream()
                        .collect(Collectors.toMap(HttpApis.CarrierType::code,
                                t -> t.compatibleVehicleTypes() == null ? Set.of() : t.compatibleVehicleTypes()));
            }

            @Override
            public List<String> waitingPoints(String mapCode) {
                return RemoteCalls.call("rcs-resource", () -> api.map(mapCode)).data().points().stream()
                        .filter(p -> p.type() == PointType.NORMAL || p.type() == PointType.PARKING
                                || p.type() == PointType.WAITING)
                        .map(p -> p.code()).toList();
            }

            @Override
            public List<String> parkingPoints(String mapCode) {
                return RemoteCalls.call("rcs-resource", () -> api.map(mapCode)).data().points().stream()
                        .filter(p -> p.type() == PointType.PARKING).map(p -> p.code()).toList();
            }

            @Override
            public List<String> chargingPoints(String mapCode) {
                return RemoteCalls.call("rcs-resource", () -> api.map(mapCode)).data().points().stream()
                        .filter(p -> p.type() == PointType.CHARGING).map(p -> p.code()).toList();
            }
        };
    }

    static Ports.TrafficGateway traffic(HttpApis.TrafficApi api) {
        return new Ports.TrafficGateway() {
            @Override
            public RouteViewDto requestRoute(String vehicleCode, String point, List<VehicleActionDto> targetActions) {
                return RemoteCalls.call("rcs-traffic",
                        () -> api.requestRoute(new RouteRequestDto(vehicleCode, point, targetActions))).data();
            }

            @Override
            public void cancelRoute(String vehicleCode) {
                RemoteCalls.call("rcs-traffic", () -> api.cancel(vehicleCode));
            }

            @Override
            public Optional<RouteViewDto> route(String vehicleCode) {
                try {
                    return Optional.of(RemoteCalls.call("rcs-traffic", () -> api.route(vehicleCode)).data());
                } catch (RemoteCallException e) {
                    if (e.errorCode().equals("NO_ACTIVE_ROUTE")) {
                        return Optional.empty();
                    }
                    throw e;
                }
            }

            @Override
            public CostMatrixDto costs(CostQueryDto query) {
                return RemoteCalls.call("rcs-traffic", () -> api.costs(query)).data();
            }
        };
    }

    static Ports.FleetGateway fleet(HttpApis.FleetApi api) {
        return vehicleCode -> RemoteCalls.call("rcs-fleet", () -> api.stopCharging(vehicleCode));
    }

    private static <T> Optional<T> optional(java.util.function.Supplier<T> call) {
        try {
            return Optional.of(call.get());
        } catch (RemoteCallException e) {
            if (RemoteCalls.isNotFound(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
