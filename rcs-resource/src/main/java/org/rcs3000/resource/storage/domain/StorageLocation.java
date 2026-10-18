package org.rcs3000.resource.storage.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

import org.rcs3000.common.error.BizException;

/**
 * A place for exactly one carrier, served from a map point (requirement STO-04).
 *
 * <pre>
 * EMPTY --lockInbound--> LOCKED_IN --completeInbound--> OCCUPIED --lockOutbound--> LOCKED_OUT --completeOutbound--> EMPTY
 *              ^------------unlock------'                    ^-----------------unlock--------------'
 * any unlocked status <--> DISABLED
 * </pre>
 *
 * Only the lock owner (normally a transport order) may complete or release a lock.
 */
public class StorageLocation {

    private Long id;
    private final String code;
    private String zoneCode;
    private StorageLocationType type;
    private String mapCode;
    private String accessPoint;
    private Double accessTheta;
    private int level;
    private double height;
    private Set<String> allowedCarrierTypes;
    private StorageLocationStatus status;
    private StorageLocationStatus statusBeforeDisable;
    private String lockOwner;
    private String carrierCode;
    private int version;
    private final Instant createdAt;
    private Instant updatedAt;

    public StorageLocation(Long id, String code, String zoneCode, StorageLocationType type, String mapCode,
            String accessPoint, Double accessTheta, int level, double height, Set<String> allowedCarrierTypes,
            StorageLocationStatus status, StorageLocationStatus statusBeforeDisable, String lockOwner,
            String carrierCode, int version, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.code = Objects.requireNonNull(code);
        this.zoneCode = zoneCode;
        this.type = type;
        this.mapCode = mapCode;
        this.accessPoint = accessPoint;
        this.accessTheta = accessTheta;
        this.level = level;
        this.height = height;
        this.allowedCarrierTypes = Set.copyOf(allowedCarrierTypes);
        this.status = status;
        this.statusBeforeDisable = statusBeforeDisable;
        this.lockOwner = lockOwner;
        this.carrierCode = carrierCode;
        this.version = version;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StorageLocation create(String code, String zoneCode, StorageLocationType type, String mapCode,
            String accessPoint, Double accessTheta, int level, double height, Set<String> allowedCarrierTypes,
            Instant now) {
        return new StorageLocation(null, code, zoneCode, type, mapCode, accessPoint, accessTheta, level, height,
                allowedCarrierTypes, StorageLocationStatus.EMPTY, null, null, null, 0, now, now);
    }

    public void redefine(String zoneCode, StorageLocationType type, String mapCode, String accessPoint,
            Double accessTheta, int level, double height, Set<String> allowedCarrierTypes, Instant now) {
        requireUnlocked("be changed");
        this.zoneCode = zoneCode;
        this.type = type;
        this.mapCode = mapCode;
        this.accessPoint = accessPoint;
        this.accessTheta = accessTheta;
        this.level = level;
        this.height = height;
        this.allowedCarrierTypes = Set.copyOf(allowedCarrierTypes);
        this.updatedAt = now;
    }

    /** Whether a carrier of this type may be stored here; no restriction when the set is empty. */
    public boolean accepts(String carrierType) {
        return allowedCarrierTypes.isEmpty() || allowedCarrierTypes.contains(carrierType);
    }

    public void lockInbound(String owner, String carrierType, Instant now) {
        requireStatus(StorageLocationStatus.EMPTY, "be locked for inbound");
        if (!accepts(carrierType)) {
            throw new BizException(StorageErrorCode.CARRIER_TYPE_NOT_ALLOWED,
                    "location %s does not accept carrier type %s".formatted(code, carrierType));
        }
        lock(StorageLocationStatus.LOCKED_IN, owner, now);
    }

    public void lockOutbound(String owner, Instant now) {
        requireStatus(StorageLocationStatus.OCCUPIED, "be locked for outbound");
        lock(StorageLocationStatus.LOCKED_OUT, owner, now);
    }

    public void completeInbound(String owner, String carrier, Instant now) {
        requireStatus(StorageLocationStatus.LOCKED_IN, "receive a carrier");
        requireOwner(owner);
        status = StorageLocationStatus.OCCUPIED;
        carrierCode = carrier;
        lockOwner = null;
        updatedAt = now;
    }

    /** @return the carrier that left */
    public String completeOutbound(String owner, Instant now) {
        requireStatus(StorageLocationStatus.LOCKED_OUT, "release its carrier");
        requireOwner(owner);
        String left = carrierCode;
        status = StorageLocationStatus.EMPTY;
        carrierCode = null;
        lockOwner = null;
        updatedAt = now;
        return left;
    }

    /** Gives up a lock without completing it, e.g. when the transport order is cancelled. */
    public void unlock(String owner, Instant now) {
        if (!status.isLocked()) {
            throw invalidState("be unlocked");
        }
        requireOwner(owner);
        status = status == StorageLocationStatus.LOCKED_IN ? StorageLocationStatus.EMPTY : StorageLocationStatus.OCCUPIED;
        lockOwner = null;
        updatedAt = now;
    }

    /** Records a carrier put here by hand (CAR-02). */
    public void place(String carrier, Instant now) {
        requireStatus(StorageLocationStatus.EMPTY, "take a carrier");
        status = StorageLocationStatus.OCCUPIED;
        carrierCode = carrier;
        updatedAt = now;
    }

    /** Records a carrier taken away by hand (CAR-02). */
    public void remove(String carrier, Instant now) {
        requireStatus(StorageLocationStatus.OCCUPIED, "give up a carrier");
        if (!carrier.equals(carrierCode)) {
            throw invalidState("give up carrier " + carrier + " (it holds " + carrierCode + ")");
        }
        status = StorageLocationStatus.EMPTY;
        carrierCode = null;
        updatedAt = now;
    }

    public void disable(Instant now) {
        if (status == StorageLocationStatus.DISABLED) {
            return;
        }
        requireUnlocked("be disabled");
        statusBeforeDisable = status;
        status = StorageLocationStatus.DISABLED;
        updatedAt = now;
    }

    public void enable(Instant now) {
        if (status != StorageLocationStatus.DISABLED) {
            return;
        }
        status = statusBeforeDisable == null ? StorageLocationStatus.EMPTY : statusBeforeDisable;
        statusBeforeDisable = null;
        updatedAt = now;
    }

    private void lock(StorageLocationStatus lockStatus, String owner, Instant now) {
        if (owner == null || owner.isBlank()) {
            throw new IllegalArgumentException("lock owner is required");
        }
        status = lockStatus;
        lockOwner = owner;
        updatedAt = now;
    }

    private void requireStatus(StorageLocationStatus expected, String action) {
        if (status != expected) {
            throw invalidState(action);
        }
    }

    private void requireUnlocked(String action) {
        if (status.isLocked()) {
            throw invalidState(action);
        }
    }

    private void requireOwner(String owner) {
        if (!Objects.equals(owner, lockOwner)) {
            throw new BizException(StorageErrorCode.LOCK_OWNER_MISMATCH,
                    "location %s is locked by %s, not %s".formatted(code, lockOwner, owner));
        }
    }

    private BizException invalidState(String action) {
        return new BizException(StorageErrorCode.LOCATION_STATE_INVALID,
                "location %s is %s and cannot %s".formatted(code, status, action));
    }

    public void assignId(Long id) {
        this.id = id;
    }

    /** Called by the repository after a successful optimistic update. */
    public void versionStored(int newVersion) {
        this.version = newVersion;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getZoneCode() { return zoneCode; }
    public StorageLocationType getType() { return type; }
    public String getMapCode() { return mapCode; }
    public String getAccessPoint() { return accessPoint; }
    public Double getAccessTheta() { return accessTheta; }
    public int getLevel() { return level; }
    public double getHeight() { return height; }
    public Set<String> getAllowedCarrierTypes() { return allowedCarrierTypes; }
    public StorageLocationStatus getStatus() { return status; }
    public StorageLocationStatus getStatusBeforeDisable() { return statusBeforeDisable; }
    public String getLockOwner() { return lockOwner; }
    public String getCarrierCode() { return carrierCode; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
