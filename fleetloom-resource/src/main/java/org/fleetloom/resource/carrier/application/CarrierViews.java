package org.fleetloom.resource.carrier.application;

import java.time.Instant;
import java.util.Set;

import org.fleetloom.resource.carrier.domain.Carrier;
import org.fleetloom.resource.carrier.domain.CarrierCategory;
import org.fleetloom.resource.carrier.domain.CarrierLocationType;
import org.fleetloom.resource.carrier.domain.CarrierStatus;
import org.fleetloom.resource.carrier.domain.CarrierType;

public final class CarrierViews {

    private CarrierViews() {
    }

    public record CarrierTypeView(String code, String name, CarrierCategory category, double length, double width,
            double height, double maxLoad, Set<String> compatibleVehicleTypes) {

        static CarrierTypeView of(CarrierType t) {
            return new CarrierTypeView(t.code(), t.name(), t.category(), t.length(), t.width(), t.height(), t.maxLoad(),
                    t.compatibleVehicleTypes());
        }
    }

    public record CarrierView(String code, String typeCode, CarrierStatus status, CarrierLocationType locationType,
            String locationRef, Instant lastMovedAt) {

        static CarrierView of(Carrier c) {
            return new CarrierView(c.getCode(), c.getTypeCode(), c.getStatus(), c.getLocationType(), c.getLocationRef(),
                    c.getLastMovedAt());
        }
    }

}
