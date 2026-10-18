package org.rcs3000.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ReservationTableTest {

    private final ReservationTable table = new ReservationTable();
    private final Resource a = Resource.node("A");
    private final Resource b = Resource.node("B");
    private final Resource ab = Resource.path("AB");

    @Test
    void reservationIsAllOrNothing() {
        assertThat(table.tryReserve("V1", List.of(a))).isTrue();

        assertThat(table.tryReserve("V2", List.of(ab, a))).isFalse();
        assertThat(table.holder(ab)).isEmpty();
        assertThat(table.blocker("V2", List.of(ab, a))).hasValueSatisfying(e -> {
            assertThat(e.getKey()).isEqualTo(a);
            assertThat(e.getValue()).isEqualTo("V1");
        });
    }

    @Test
    void reservingWhatOneAlreadyHoldsSucceeds() {
        table.tryReserve("V1", List.of(a));

        assertThat(table.tryReserve("V1", List.of(a, ab))).isTrue();
        assertThat(table.heldBy("V1")).containsExactlyInAnyOrder(a, ab);
    }

    @Test
    void retainOnlyReleasesTheRest() {
        table.tryReserve("V1", List.of(a, ab, b));

        table.retainOnly("V1", Set.of(b));

        assertThat(table.heldBy("V1")).containsExactly(b);
        assertThat(table.tryReserve("V2", List.of(a, ab))).isTrue();
    }

    @Test
    void forceReserveTakesOverAndReportsConflicts() {
        table.tryReserve("V1", List.of(a, ab));

        Set<Resource> conflicts = table.forceReserve("V2", List.of(a, b));

        assertThat(conflicts).containsExactly(a);
        assertThat(table.holder(a)).contains("V2");
        assertThat(table.heldBy("V1")).containsExactly(ab);
    }
}
