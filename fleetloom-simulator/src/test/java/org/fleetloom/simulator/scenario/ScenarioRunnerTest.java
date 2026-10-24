package org.fleetloom.simulator.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.fleetloom.contracts.dispatch.CreateOrderDto;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderViewDto;
import org.fleetloom.simulator.app.SimulatorProperties;
import tools.jackson.databind.json.JsonMapper;

/** The runner against a fake dispatch that completes every order on the second look. */
class ScenarioRunnerTest {

    private final FakePlatform platform = new FakePlatform();
    private final SimulatorProperties properties = new SimulatorProperties(null, "FLEETLOOM-SIM", 50, 1, 1, 0.1,
            new SimulatorProperties.MapSettings("", "DEMO"), null, List.of());
    private final ScenarioRunner runner = new ScenarioRunner(null, platform, properties, Clock.systemUTC(),
            Duration.ofMillis(20));

    @AfterEach
    void stop() {
        runner.close();
    }

    @Test
    void sendsTheOrdersOnScheduleAndReportsWhenAllAreDone() {
        runner.start(Scenario.parse("""
                name: t1
                orders:
                  - {every: 30ms, count: 4, type: MOVE, targetPoints: [P00, P22]}
                  - {start: 10ms, type: TRANSPORT, carriers: [S1], targetZone: A}
                  - {start: 20ms, type: TRANSPORT, carriers: [LOCKED], targetZone: A}
                """, JsonMapper.builder().build()));

        await().atMost(Duration.ofSeconds(5)).until(() -> runner.current().orElseThrow().state().equals("FINISHED"));

        ScenarioRun.Report report = runner.current().orElseThrow();
        assertThat(report.planned()).isEqualTo(6);
        assertThat(report.sent()).isEqualTo(6);
        assertThat(report.completed()).isEqualTo(5);
        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.errors()).singleElement().asString().contains("carrier LOCKED is busy");
        assertThat(report.avgSeconds()).isEqualTo(12.0);
        assertThat(platform.created).extracting(CreateOrderDto::targetPoint)
                .containsSubsequence("P00", "P22", "P00", "P22");
        assertThat(platform.created).allSatisfy(o -> assertThat(o.externalNo()).startsWith("t1-"));
        assertThat(platform.created).filteredOn(o -> o.targetPoint() != null)
                .allSatisfy(o -> assertThat(o.mapCode()).isEqualTo("DEMO"));
    }

    @Test
    void onlyOneRunAtATimeAndAStopEndsIt() {
        Scenario slow = Scenario.parse("{orders: [{every: 1h, count: 3, type: MOVE, targetPoints: [P00]}]}",
                JsonMapper.builder().build());
        runner.start(slow);

        assertThatThrownBy(() -> runner.start(slow)).isInstanceOf(IllegalStateException.class);
        assertThat(runner.stop().orElseThrow().state()).isEqualTo("STOPPED");
        runner.start(slow);
    }

    @Test
    void playsInboundOutboundAndPickingAroundTheOrders() {
        platform.zone("IN", "IN-1");
        platform.zone("STORE", "ST-1", "ST-2", "ST-3");
        platform.zone("OUT", "OUT-1");
        platform.zone("PICK", "PS-1");
        platform.put("SH1", "ST-1", "SKU-A");
        platform.put("SH2", "ST-2", "SKU-B");

        runner.start(Scenario.parse("""
                name: wh
                orders:
                  - {name: inbound, every: 10ms, count: 2, type: TRANSPORT, targetZone: STORE,
                     arrive: {zone: IN, carrierType: SHELF, goods: [SKU-C]}}
                  - {name: outbound, start: 200ms, type: TRANSPORT, fromZone: STORE, sku: SKU-A, targetZone: OUT,
                     depart: 10ms}
                  - {name: picking, start: 200ms, every: 10ms, count: 2, type: TRANSPORT, fromZone: STORE,
                     targetZone: PICK, returnAfter: 10ms}
                """, JsonMapper.builder().build()));

        await().atMost(Duration.ofSeconds(5)).until(() -> runner.current().orElseThrow().state().equals("FINISHED"));

        ScenarioRun.Report report = runner.current().orElseThrow();
        assertThat(report.streams()).extracting(ScenarioRun.StreamReport::name)
                .containsExactly("inbound", "outbound", "picking", "picking (return)");
        ScenarioRun.StreamReport inbound = report.streams().get(0);
        assertThat(inbound.completed()).isEqualTo(2);
        assertThat(inbound.waited()).as("one dock: the second truck waits for the first shelf to leave").isEqualTo(1);
        ScenarioRun.StreamReport outbound = report.streams().get(1);
        assertThat(outbound.completed()).isEqualTo(1);
        assertThat(outbound.departed()).isEqualTo(1);
        assertThat(report.streams().get(2).completed()).isEqualTo(2);
        assertThat(report.streams().get(2).waited()).as("one station: the second shelf waits").isEqualTo(1);
        assertThat(report.streams().get(3).completed()).isEqualTo(2);
        assertThat(report.rejected()).isZero();

        assertThat(platform.carriers).doesNotContainKey("SH1").as("shipped");
        assertThat(platform.goods.values()).contains("SKU-C");
        assertThat(platform.carriers.values()).allSatisfy(at -> assertThat(at).startsWith("ST-"));
    }

    @Test
    void anOrderThatFindsNothingToMoveIsGivenUpAfterMaxWait() {
        platform.zone("STORE", "ST-1");
        platform.zone("OUT", "OUT-1");
        platform.put("SH1", "ST-1", "SKU-A");

        runner.start(Scenario.parse("""
                orders:
                  - {type: TRANSPORT, fromZone: STORE, sku: SKU-B, targetZone: OUT, maxWait: 100ms}
                """, JsonMapper.builder().build()));

        await().atMost(Duration.ofSeconds(5)).until(() -> runner.current().orElseThrow().state().equals("FINISHED"));
        ScenarioRun.Report report = runner.current().orElseThrow();
        assertThat(report.rejected()).isEqualTo(1);
        assertThat(report.errors()).singleElement().asString()
                .contains("gave up after waiting PT0.1S for an idle carrier in STORE holding SKU-B");
        assertThat(platform.created).isEmpty();
    }

    @Test
    void ordersAreSentAgainWhileDispatchDoesNotAnswer() {
        platform.unreachable.set(2);

        runner.start(Scenario.parse("{orders: [{type: MOVE, targetPoints: [P00]}]}", JsonMapper.builder().build()));

        await().atMost(Duration.ofSeconds(5)).until(() -> runner.current().orElseThrow().state().equals("FINISHED"));
        ScenarioRun.Report report = runner.current().orElseThrow();
        assertThat(report.completed()).isEqualTo(1);
        assertThat(report.rejected()).isZero();
        assertThat(platform.created).hasSize(3).extracting(CreateOrderDto::externalNo).containsOnly(
                platform.created.getFirst().externalNo());
    }

    static final class FakePlatform implements Platform {

        /** this many order requests fail as if dispatch did not answer */
        final AtomicInteger unreachable = new AtomicInteger();

        final List<CreateOrderDto> created = new CopyOnWriteArrayList<>();
        private final Map<String, Integer> looks = new ConcurrentHashMap<>();
        private final AtomicInteger numbers = new AtomicInteger();
        /** location → zone, in the order they were added */
        final Map<String, String> locations = new java.util.LinkedHashMap<>();
        /** carrier → location; locations reserved for a carrier on its way are in {@code incoming} */
        final Map<String, String> carriers = new ConcurrentHashMap<>();
        final Map<String, String> goods = new ConcurrentHashMap<>();
        private final Map<String, String> incoming = new ConcurrentHashMap<>();
        private final Map<String, CreateOrderDto> orders = new ConcurrentHashMap<>();

        void zone(String zone, String... codes) {
            for (String c : codes) {
                locations.put(c, zone);
            }
        }

        void put(String carrier, String location, String sku) {
            carriers.put(carrier, location);
            goods.put(carrier, sku);
        }

        @Override
        public synchronized OrderViewDto createOrder(CreateOrderDto order) {
            created.add(order);
            if (unreachable.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw new org.springframework.web.client.ResourceAccessException("connection refused");
            }
            String no = "TO-" + numbers.incrementAndGet();
            if ("LOCKED".equals(order.carrierCode())) {
                return view(no, OrderStatus.REJECTED, "carrier LOCKED is busy");
            }
            if (order.targetZone() != null && locations.containsValue(order.targetZone())) {
                String target = locations.keySet().stream()
                        .filter(l -> order.targetZone().equals(locations.get(l)) && free(l)).findFirst().orElse(null);
                if (target == null) {
                    return view(no, OrderStatus.REJECTED, "zone full");
                }
                incoming.put(target, order.carrierCode());
            }
            orders.put(no, order);
            return view(no, OrderStatus.QUEUED, null);
        }

        @Override
        public synchronized OrderViewDto order(String orderNo) {
            int n = looks.merge(orderNo, 1, Integer::sum);
            if (n < 2) {
                return view(orderNo, OrderStatus.EXECUTING, null);
            }
            CreateOrderDto order = orders.get(orderNo);
            if (order != null && order.carrierCode() != null) {
                incoming.entrySet().stream().filter(e -> e.getValue().equals(order.carrierCode())).findFirst()
                        .ifPresent(e -> {
                            carriers.put(order.carrierCode(), e.getKey());
                            incoming.remove(e.getKey());
                        });
            }
            return view(orderNo, OrderStatus.COMPLETED, null);
        }

        @Override
        public void registerVehicle(String code, String typeCode, String manufacturer, String initialPoint) {
        }

        @Override
        public synchronized List<Location> locations(String zone) {
            return locations.keySet().stream().filter(l -> zone.equals(locations.get(l)))
                    .map(l -> new Location(l, free(l) ? "EMPTY" : incoming.containsKey(l) ? "LOCKED_IN" : "OCCUPIED",
                            carriers.entrySet().stream().filter(e -> e.getValue().equals(l)).map(Map.Entry::getKey)
                                    .findFirst().orElse(null)))
                    .toList();
        }

        @Override
        public List<String> carriersWithGoods(String sku) {
            return goods.entrySet().stream().filter(e -> e.getValue().startsWith(sku)).map(Map.Entry::getKey).toList();
        }

        @Override
        public synchronized void createCarrier(String code, String typeCode, String location, List<GoodsLine> lines) {
            carriers.put(code, location);
            lines.forEach(g -> goods.put(code, g.sku()));
        }

        @Override
        public synchronized void removeCarrier(String code) {
            carriers.remove(code);
            goods.remove(code);
        }

        private boolean free(String location) {
            return !carriers.containsValue(location) && !incoming.containsKey(location);
        }

        private static OrderViewDto view(String no, OrderStatus status, String reason) {
            Instant created = Instant.parse("2026-10-18T08:00:00Z");
            return new OrderViewDto(no, null, null, 5, status, null, null, null, null, null, 0, List.of(), reason,
                    created, created, status == OrderStatus.COMPLETED ? created.plusSeconds(12) : null, null);
        }
    }
}
