package org.fleetloom.resource.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.dispatch.OrderOverdueEvent;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderStatusChangedEvent;
import org.fleetloom.contracts.dispatch.OrderType;
import org.fleetloom.contracts.dispatch.OrderViewDto;
import org.fleetloom.contracts.fleet.ConnectionStatus;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleForgottenEvent;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.contracts.traffic.DeadlockDetectedEvent;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.RouteStatus;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.fleetloom.resource.system.application.AlarmRules;
import org.fleetloom.resource.system.application.AlarmService;
import org.fleetloom.resource.system.domain.Alarm;
import org.fleetloom.resource.system.domain.AlarmCategory;
import org.fleetloom.resource.system.domain.AlarmLevel;
import org.fleetloom.resource.system.domain.AlarmRepository;
import org.fleetloom.resource.system.domain.AlarmStatus;

class AlarmRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-18T08:00:00Z");

    private final InMemoryAlarms repo = new InMemoryAlarms();
    private final List<Alarm> notified = new ArrayList<>();
    private final AlarmService alarms = new AlarmService(repo, notified::add, Clock.fixed(NOW, ZoneOffset.UTC));
    private final AlarmRules rules = new AlarmRules(alarms);

    @Test
    void vehicleErrorsOpenAndCloseWithTheStateMessages() {
        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("noRoute", "WARNING", "blocked", null)));
        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("noRoute", "WARNING", "blocked", null)));
        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("noRoute", "WARNING", "blocked", null)));

        assertThat(open()).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(AlarmCategory.VEHICLE_ERROR);
            assertThat(a.level()).isEqualTo(AlarmLevel.WARNING);
            assertThat(a.vehicleCode()).isEqualTo("AGV-1");
            assertThat(a.message()).isEqualTo("noRoute: blocked");
        });
        // the same report every second is not news
        assertThat(notified).hasSize(1);

        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("noRoute", "FATAL", "blocked", null)));
        assertThat(open()).singleElement().satisfies(a -> {
            assertThat(a.level()).isEqualTo(AlarmLevel.FATAL);
            assertThat(a.occurrences()).isEqualTo(2);
        });

        rules.on(state("AGV-1", ConnectionStatus.ONLINE));
        assertThat(open()).isEmpty();
        assertThat(repo.rows).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AlarmStatus.CLEARED);
            assertThat(a.clearedBy()).isNull();
        });
    }

    @Test
    void errorsOfOtherVehiclesAreLeftAlone() {
        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("e1", "WARNING", null, null)));
        rules.on(state("AGV-10", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("e1", "WARNING", null, null)));
        rules.on(state("AGV-1", ConnectionStatus.ONLINE));

        assertThat(open()).singleElement().satisfies(a -> assertThat(a.vehicleCode()).isEqualTo("AGV-10"));
    }

    @Test
    void aRemovedVehicleTakesItsOwnAlarmsWithIt() {
        rules.on(state("AGV-1", ConnectionStatus.ONLINE, new VehicleStatusDto.VehicleError("e1", "WARNING", null, null)));
        rules.on(new VehicleConnectionChangedEvent("AGV-1", ConnectionStatus.ONLINE, ConnectionStatus.OFFLINE, null, NOW));
        rules.on(new VehicleConnectionChangedEvent("AGV-2", ConnectionStatus.ONLINE, ConnectionStatus.OFFLINE, null, NOW));
        assertThat(open()).hasSize(3);

        rules.on(new VehicleForgottenEvent("AGV-1", NOW));

        assertThat(open()).singleElement().satisfies(a -> assertThat(a.vehicleCode()).isEqualTo("AGV-2"));
    }

    @Test
    void offlineVehicleIsAlarmedUntilItIsBack() {
        rules.on(new VehicleConnectionChangedEvent("AGV-1", ConnectionStatus.ONLINE, ConnectionStatus.CONNECTION_BROKEN,
                "no state for 5 s", NOW));
        assertThat(open()).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(AlarmCategory.VEHICLE_OFFLINE);
            assertThat(a.level()).isEqualTo(AlarmLevel.ERROR);
            assertThat(a.message()).contains("no state for 5 s");
        });

        rules.on(state("AGV-1", ConnectionStatus.ONLINE));
        assertThat(open()).isEmpty();
    }

    @Test
    void blockageAndDeadlockEndWhenTheVehicleMovesOn() {
        rules.on(new TrafficBlockedEvent("AGV-1", "AGV-2", "NODE:P11", NOW));
        rules.on(new DeadlockDetectedEvent(List.of("AGV-2", "AGV-1"), NOW));
        rules.on(new DeadlockDetectedEvent(List.of("AGV-3", "AGV-4"), NOW));
        assertThat(open()).extracting(Alarm::category)
                .containsExactlyInAnyOrder(AlarmCategory.TRAFFIC_BLOCKED, AlarmCategory.DEADLOCK, AlarmCategory.DEADLOCK);

        rules.on(progress("AGV-1", RouteStatus.WAITING));
        assertThat(open()).hasSize(3);

        rules.on(progress("AGV-1", RouteStatus.MOVING));
        assertThat(open()).singleElement().satisfies(a -> assertThat(a.alarmKey()).isEqualTo("DEADLOCK:AGV-3,AGV-4"));
    }

    @Test
    void suspendedOrdersAreAlarmedUntilResumedAndFailedOnesUntilClosed() {
        rules.on(order("T-1", OrderStatus.SUSPENDED, "vehicle AGV-1 went offline"));
        assertThat(open()).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(AlarmCategory.ORDER_SUSPENDED);
            assertThat(a.orderNo()).isEqualTo("T-1");
            assertThat(a.message()).isEqualTo("vehicle AGV-1 went offline");
        });

        rules.on(order("T-1", OrderStatus.EXECUTING, null));
        assertThat(open()).isEmpty();

        rules.on(order("T-1", OrderStatus.FAILED, "carrier lost"));
        Alarm failed = open().getFirst();
        assertThat(failed.category()).isEqualTo(AlarmCategory.ORDER_FAILED);

        assertThat(alarms.acknowledge(failed.id(), "op").status()).isEqualTo(AlarmStatus.ACKNOWLEDGED);
        assertThat(alarms.summary().unacknowledged()).isZero();
        assertThat(alarms.summary().open()).isOne();

        Alarm closed = alarms.close(failed.id(), "op");
        assertThat(closed.status()).isEqualTo(AlarmStatus.CLEARED);
        assertThat(closed.clearedBy()).isEqualTo("op");
        assertThat(alarms.summary().open()).isZero();
    }

    @Test
    void overdueOrderIsAlarmedUntilItFinishes() {
        OrderStatusChangedEvent executing = order("T-9", OrderStatus.EXECUTING, null);
        rules.on(new OrderOverdueEvent(executing.order()));
        assertThat(open()).singleElement().satisfies(a -> {
            assertThat(a.category()).isEqualTo(AlarmCategory.ORDER_OVERDUE);
            assertThat(a.level()).isEqualTo(AlarmLevel.WARNING);
        });
        rules.on(order("T-9", OrderStatus.SUSPENDED, "paused"));
        assertThat(open()).extracting(Alarm::category).contains(AlarmCategory.ORDER_OVERDUE);

        rules.on(order("T-9", OrderStatus.COMPLETED, null));
        assertThat(open()).isEmpty();
    }

    @Test
    void aConditionThatComesBackIsANewAlarm() {
        rules.on(new TrafficBlockedEvent("AGV-1", "AGV-2", "NODE:P11", NOW));
        rules.on(progress("AGV-1", RouteStatus.MOVING));
        rules.on(new TrafficBlockedEvent("AGV-1", "AGV-2", "NODE:P11", NOW));

        assertThat(repo.rows).hasSize(2);
        assertThat(open()).hasSize(1);
    }

    @Test
    void openAlarmsSurviveARestart() {
        rules.on(new TrafficBlockedEvent("AGV-1", "AGV-2", "NODE:P11", NOW));
        AlarmRules restarted = new AlarmRules(new AlarmService(repo, a -> { }, Clock.fixed(NOW, ZoneOffset.UTC)));

        restarted.on(new TrafficBlockedEvent("AGV-1", "AGV-2", "NODE:P11", NOW));
        assertThat(repo.rows).hasSize(1);
        restarted.on(progress("AGV-1", RouteStatus.ARRIVED));
        assertThat(open()).isEmpty();
    }

    @Test
    void unknownAlarmIsReported() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> alarms.acknowledge(99, "op"))
                .isInstanceOf(BizException.class);
    }

    private List<Alarm> open() {
        return repo.findOpen();
    }

    private static VehicleStateUpdatedEvent state(String code, ConnectionStatus connection, VehicleStatusDto.VehicleError... errors) {
        return new VehicleStateUpdatedEvent(new VehicleStatusDto(code, "SIM", code, connection, NOW, "DEMO", 0, 0, 0, true,
                0, false, false, "AUTOMATIC", null, 0, "P00", 0, List.of(), 90, false, List.of(), List.of(), List.of(errors)));
    }

    private static RouteProgressedEvent progress(String vehicle, RouteStatus status) {
        return new RouteProgressedEvent("R-1", vehicle, "P12", status, "P11", null, NOW);
    }

    private static OrderStatusChangedEvent order(String no, OrderStatus status, String reason) {
        return new OrderStatusChangedEvent(new OrderViewDto(no, null, OrderType.TRANSPORT, 5, status, "C-1", "L1", "L2", "DEMO",
                "AGV-1", 0, List.of(), reason, NOW, NOW, null, null), null);
    }

    static final class InMemoryAlarms implements AlarmRepository {

        final List<Alarm> rows = new ArrayList<>();

        @Override
        public Optional<Alarm> findById(long id) {
            return rows.stream().filter(a -> a.id() == id).findFirst();
        }

        @Override
        public List<Alarm> findOpen() {
            return rows.stream().filter(Alarm::open).toList();
        }

        @Override
        public List<Alarm> find(Boolean open, AlarmLevel level, String vehicleCode, int limit) {
            return rows.stream().filter(a -> open == null || a.open() == open)
                    .sorted(Comparator.comparing(Alarm::raisedAt).reversed()).limit(limit).toList();
        }

        @Override
        public Alarm save(Alarm alarm) {
            if (alarm.id() == null) {
                Alarm saved = alarm.withId(rows.size() + 1);
                rows.add(saved);
                return saved;
            }
            rows.replaceAll(a -> a.id().equals(alarm.id()) ? alarm : a);
            return alarm;
        }
    }
}
