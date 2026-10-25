package org.fleetloom.simulator.platform;

import java.util.List;
import java.util.Optional;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.simulator.app.VehicleSpeeds;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** The top speed of the vehicle type a vehicle has in fleetloom-resource's registry. */
final class RegistrySpeeds implements VehicleSpeeds {

    private final RestClient resource;

    RegistrySpeeds(RestClient resource) {
        this.resource = resource;
    }

    @Override
    public Optional<Double> maxSpeed(String manufacturer, String serialNumber) {
        ApiResponse<List<Vehicle>> vehicles = resource.get().uri("/api/resource/v1/vehicles").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        Optional<String> type = vehicles == null || vehicles.data() == null ? Optional.empty() : vehicles.data().stream()
                .filter(v -> manufacturer.equals(v.manufacturer()) && serialNumber.equals(v.serialNumber()))
                .map(Vehicle::typeCode).findFirst();
        if (type.isEmpty()) {
            return Optional.empty();
        }
        ApiResponse<List<VehicleType>> types = resource.get().uri("/api/resource/v1/vehicle-types").retrieve()
                .body(new ParameterizedTypeReference<>() { });
        return types == null || types.data() == null ? Optional.empty() : types.data().stream()
                .filter(t -> t.code().equals(type.get()) && t.maxSpeed() > 0).map(VehicleType::maxSpeed).findFirst();
    }

    private record Vehicle(String typeCode, String manufacturer, String serialNumber) {
    }

    private record VehicleType(String code, double maxSpeed) {
    }
}
