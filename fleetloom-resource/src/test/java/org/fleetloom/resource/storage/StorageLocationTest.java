package org.fleetloom.resource.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Set;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.storage.domain.StorageErrorCode;
import org.fleetloom.resource.storage.domain.StorageLocation;
import org.fleetloom.resource.storage.domain.StorageLocationStatus;
import org.fleetloom.resource.storage.domain.StorageLocationType;

class StorageLocationTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    private static StorageLocation location(Set<String> allowed) {
        return StorageLocation.create("L1", "Z", StorageLocationType.GROUND, "WH1", "P1", null, 0, 0, allowed, NOW);
    }

    @Test
    void fullInboundAndOutboundCycle() {
        StorageLocation l = location(Set.of());

        l.lockInbound("order-1", "PALLET", NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.LOCKED_IN);
        assertThat(l.getLockOwner()).isEqualTo("order-1");

        l.completeInbound("order-1", "C1", NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.OCCUPIED);
        assertThat(l.getCarrierCode()).isEqualTo("C1");
        assertThat(l.getLockOwner()).isNull();

        l.lockOutbound("order-2", NOW);
        assertThat(l.completeOutbound("order-2", NOW)).isEqualTo("C1");
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.EMPTY);
        assertThat(l.getCarrierCode()).isNull();
    }

    @Test
    void onlyTheOwnerCompletesOrReleasesALock() {
        StorageLocation l = location(Set.of());
        l.lockInbound("order-1", "PALLET", NOW);

        assertError(() -> l.completeInbound("order-2", "C1", NOW), StorageErrorCode.LOCK_OWNER_MISMATCH);
        assertError(() -> l.unlock("order-2", NOW), StorageErrorCode.LOCK_OWNER_MISMATCH);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.LOCKED_IN);
    }

    @Test
    void unlockReturnsToThePreviousState() {
        StorageLocation empty = location(Set.of());
        empty.lockInbound("o", "PALLET", NOW);
        empty.unlock("o", NOW);
        assertThat(empty.getStatus()).isEqualTo(StorageLocationStatus.EMPTY);

        StorageLocation full = location(Set.of());
        full.place("C1", NOW);
        full.lockOutbound("o", NOW);
        full.unlock("o", NOW);
        assertThat(full.getStatus()).isEqualTo(StorageLocationStatus.OCCUPIED);
        assertThat(full.getCarrierCode()).isEqualTo("C1");
    }

    @Test
    void lockingRequiresTheRightState() {
        StorageLocation l = location(Set.of());

        assertError(() -> l.lockOutbound("o", NOW), StorageErrorCode.LOCATION_STATE_INVALID);
        l.lockInbound("o", "PALLET", NOW);
        assertError(() -> l.lockInbound("o2", "PALLET", NOW), StorageErrorCode.LOCATION_STATE_INVALID);
        assertError(() -> l.disable(NOW), StorageErrorCode.LOCATION_STATE_INVALID);
    }

    @Test
    void carrierTypeRestrictionIsChecked() {
        StorageLocation l = location(Set.of("SHELF"));

        assertError(() -> l.lockInbound("o", "PALLET", NOW), StorageErrorCode.CARRIER_TYPE_NOT_ALLOWED);
        l.lockInbound("o", "SHELF", NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.LOCKED_IN);
    }

    @Test
    void disablingRemembersTheStatus() {
        StorageLocation l = location(Set.of());
        l.place("C1", NOW);

        l.disable(NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.DISABLED);
        assertError(() -> l.lockOutbound("o", NOW), StorageErrorCode.LOCATION_STATE_INVALID);

        l.enable(NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.OCCUPIED);
    }

    @Test
    void manualRemovalNeedsTheRightCarrier() {
        StorageLocation l = location(Set.of());
        l.place("C1", NOW);

        assertError(() -> l.remove("C2", NOW), StorageErrorCode.LOCATION_STATE_INVALID);
        l.remove("C1", NOW);
        assertThat(l.getStatus()).isEqualTo(StorageLocationStatus.EMPTY);
    }

    @Test
    void lockedLocationCannotBeRedefined() {
        StorageLocation l = location(Set.of());
        l.lockInbound("o", "PALLET", NOW);

        assertError(() -> l.redefine("Z", StorageLocationType.RACK, "WH1", "P2", null, 1, 1.5, Set.of(), NOW),
                StorageErrorCode.LOCATION_STATE_INVALID);
    }

    private static void assertError(ThrowingCallable call, StorageErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
