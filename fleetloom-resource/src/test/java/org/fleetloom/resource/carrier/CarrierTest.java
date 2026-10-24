package org.fleetloom.resource.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.resource.carrier.domain.Carrier;
import org.fleetloom.resource.carrier.domain.CarrierLocationType;
import org.fleetloom.resource.carrier.domain.CarrierStatus;

class CarrierTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    @Test
    void travelsFromStorageOverAVehicleToStorage() {
        Carrier c = Carrier.create("C1", "PALLET", NOW);
        c.placeIn("L1", NOW);

        c.loadOnto("AGV-1", NOW);
        assertThat(c.getStatus()).isEqualTo(CarrierStatus.IN_TRANSIT);
        assertThat(c.getLocationType()).isEqualTo(CarrierLocationType.VEHICLE);
        assertThat(c.getLocationRef()).isEqualTo("AGV-1");

        c.unloadInto("L2", NOW);
        assertThat(c.getStatus()).isEqualTo(CarrierStatus.IDLE);
        assertThat(c.getLocationRef()).isEqualTo("L2");
        assertThat(c.getLastMovedAt()).isEqualTo(NOW);
    }

    @Test
    void cannotBeInTwoPlaces() {
        Carrier c = Carrier.create("C1", "PALLET", NOW);

        assertThatThrownBy(() -> c.loadOnto("AGV-1", NOW)).isInstanceOf(BizException.class);
        c.placeIn("L1", NOW);
        assertThatThrownBy(() -> c.placeIn("L2", NOW)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> c.unloadInto("L2", NOW)).isInstanceOf(BizException.class);
    }

    @Test
    void disabledCarrierDoesNotMove() {
        Carrier c = Carrier.create("C1", "PALLET", NOW);
        c.placeIn("L1", NOW);
        c.disable(NOW);

        assertThatThrownBy(() -> c.loadOnto("AGV-1", NOW)).isInstanceOf(BizException.class);
        c.enable(NOW);
        c.loadOnto("AGV-1", NOW);
        assertThatThrownBy(() -> c.disable(NOW)).isInstanceOf(BizException.class);
    }
}
