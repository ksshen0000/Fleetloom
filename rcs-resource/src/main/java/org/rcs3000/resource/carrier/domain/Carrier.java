package org.rcs3000.resource.carrier.domain;

import java.time.Instant;
import java.util.Objects;

import org.rcs3000.common.error.BizException;

/**
 * Something vehicles carry (pallet, shelf, cart). It is in exactly one place: a storage location, on a
 * vehicle, or outside the system (requirement CAR-04).
 */
public class Carrier {

    private Long id;
    private final String code;
    private final String typeCode;
    private CarrierStatus status;
    private CarrierLocationType locationType;
    private String locationRef;
    private Instant lastMovedAt;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    public Carrier(Long id, String code, String typeCode, CarrierStatus status, CarrierLocationType locationType,
            String locationRef, Instant lastMovedAt, int version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = Objects.requireNonNull(code);
        this.typeCode = Objects.requireNonNull(typeCode);
        this.status = status;
        this.locationType = locationType;
        this.locationRef = locationRef;
        this.lastMovedAt = lastMovedAt;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Carrier create(String code, String typeCode, Instant now) {
        return new Carrier(null, code, typeCode, CarrierStatus.IDLE, CarrierLocationType.NONE, null, null, 0, now, now);
    }

    /** Puts the carrier into a storage location by hand. */
    public void placeIn(String location, Instant now) {
        require(CarrierLocationType.NONE, "be placed into " + location);
        move(CarrierLocationType.STORAGE, location, CarrierStatus.IDLE, now);
    }

    /** Takes the carrier out of the system's view by hand. */
    public void takeOut(Instant now) {
        require(CarrierLocationType.STORAGE, "be taken out");
        move(CarrierLocationType.NONE, null, CarrierStatus.IDLE, now);
    }

    public void loadOnto(String vehicle, Instant now) {
        require(CarrierLocationType.STORAGE, "be loaded onto " + vehicle);
        move(CarrierLocationType.VEHICLE, vehicle, CarrierStatus.IN_TRANSIT, now);
    }

    public void unloadInto(String location, Instant now) {
        require(CarrierLocationType.VEHICLE, "be unloaded into " + location);
        move(CarrierLocationType.STORAGE, location, CarrierStatus.IDLE, now);
    }

    public void disable(Instant now) {
        if (status == CarrierStatus.IN_TRANSIT) {
            throw invalid("be disabled");
        }
        status = CarrierStatus.DISABLED;
        updatedAt = now;
    }

    public void enable(Instant now) {
        if (status == CarrierStatus.DISABLED) {
            status = CarrierStatus.IDLE;
            updatedAt = now;
        }
    }

    public void requireUsable() {
        if (status == CarrierStatus.DISABLED) {
            throw invalid("be moved");
        }
    }

    private void move(CarrierLocationType type, String ref, CarrierStatus newStatus, Instant now) {
        requireUsable();
        locationType = type;
        locationRef = ref;
        status = newStatus;
        lastMovedAt = now;
        updatedAt = now;
    }

    private void require(CarrierLocationType expected, String action) {
        if (locationType != expected) {
            throw invalid(action);
        }
    }

    private BizException invalid(String action) {
        String where = locationRef == null ? locationType.name() : locationType + " " + locationRef;
        return new BizException(CarrierErrorCode.CARRIER_STATE_INVALID,
                "carrier %s (%s, %s) cannot %s".formatted(code, status, where, action));
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public void versionStored(int newVersion) {
        this.version = newVersion;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTypeCode() { return typeCode; }
    public CarrierStatus getStatus() { return status; }
    public CarrierLocationType getLocationType() { return locationType; }
    public String getLocationRef() { return locationRef; }
    public Instant getLastMovedAt() { return lastMovedAt; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
