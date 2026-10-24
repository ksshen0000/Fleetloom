package org.fleetloom.dispatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderType;
import org.fleetloom.dispatch.core.Leg;
import org.fleetloom.dispatch.core.TransportOrder;
import org.fleetloom.dispatch.infra.MybatisOrderRepository;
import org.fleetloom.dispatch.infra.MybatisVehicleSettingsRepository;
import org.fleetloom.dispatch.infra.TransportOrderMapper;
import org.fleetloom.dispatch.infra.VehicleSettingMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Orders and vehicle settings on a real PostgreSQL, plus the REST API of a dispatcher that has no
 * vehicles. The other services are not running, so every remote call fails, which the dispatcher must
 * survive. Skipped when Docker is not available.
 */
@SpringBootTest(properties = {
        "fleetloom.dispatch.resource-url=http://localhost:1",
        "fleetloom.dispatch.traffic-url=http://localhost:1",
        "fleetloom.dispatch.fleet-url=http://localhost:1"})
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class DispatchPersistenceIntegrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl() + "&currentSchema=dispatch&stringtype=unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransportOrderMapper orderMapper;

    @Autowired
    private VehicleSettingMapper settingMapper;

    @Autowired
    private JsonMapper json;

    @Autowired
    private org.fleetloom.dispatch.app.SettingStore settingStore;

    @Test
    void orderRoundTripsWithLegsAndOptimisticLocking() {
        MybatisOrderRepository repository = new MybatisOrderRepository(orderMapper, json);
        Instant now = Instant.parse("2026-10-18T08:00:00Z");
        TransportOrder order = new TransportOrder(null, "TO-IT-1", "EXT-1", OrderType.TRANSPORT, 7, "C1", "SHELF", "L1",
                "L2", "WH1", null, "AGV-1", OrderStatus.EXECUTING, 1,
                List.of(new Leg(Leg.Kind.PICKUP, "P1", "pick", Map.of("loadId", "C1"), Leg.Status.DONE),
                        new Leg(Leg.Kind.DROP, "P2", "drop", Map.of("loadId", "C1"), Leg.Status.RUNNING)),
                "why", "route-1", now, now, null, 0).withDeadline(now.plusSeconds(600));
        repository.insert(order);

        TransportOrder loaded = repository.findByNo("TO-IT-1").orElseThrow();
        assertThat(loaded.legs()).isEqualTo(order.legs());
        assertThat(loaded.deadline()).isEqualTo(now.plusSeconds(600));
        assertThat(loaded.carrierType()).isEqualTo("SHELF");
        assertThat(repository.findByExternalNo("EXT-1")).isPresent();
        assertThat(repository.findActive()).extracting(TransportOrder::orderNo).contains("TO-IT-1");

        TransportOrder stale = repository.findByNo("TO-IT-1").orElseThrow();
        repository.update(loaded);
        assertThatThrownBy(() -> repository.update(stale)).isInstanceOf(BizException.class);

        TransportOrder cleared = new TransportOrder(loaded.id(), "TO-IT-1", "EXT-1", OrderType.TRANSPORT, 7, "C1", "SHELF",
                "L1", "L2", "WH1", null, null, OrderStatus.COMPLETED, 1, loaded.legs(), null, null, now, now, now,
                loaded.version());
        repository.update(cleared);
        TransportOrder done = repository.findByNo("TO-IT-1").orElseThrow();
        assertThat(done.assignedVehicle()).isNull();
        assertThat(done.routeId()).isNull();
        assertThat(done.finishedAt()).isEqualTo(now);
        assertThat(repository.findActive()).extracting(TransportOrder::orderNo).doesNotContain("TO-IT-1");
        assertThat(repository.find(OrderStatus.COMPLETED, 10)).extracting(TransportOrder::orderNo).contains("TO-IT-1");
    }

    @Test
    void acceptingSettingIsStored() {
        MybatisVehicleSettingsRepository repository = new MybatisVehicleSettingsRepository(settingMapper, Clock.systemUTC());

        repository.setAccepting("AGV-9", false);
        assertThat(repository.notAccepting()).contains("AGV-9");
        repository.setAccepting("AGV-9", true);
        assertThat(repository.notAccepting()).doesNotContain("AGV-9");
    }

    @Test
    void moveOrderWaitsWithoutVehiclesAndCanBeCancelled() throws Exception {
        String orderNo = json.readTree(mvc.perform(post("/api/dispatch/v1/orders").contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"type": "MOVE", "mapCode": "WH1", "targetPoint": "P7", "priority": 3}"""))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.data.status").value("QUEUED"))
                        .andReturn().getResponse().getContentAsString())
                .path("data").path("orderNo").asString();

        mvc.perform(get("/api/dispatch/v1/orders/" + orderNo)).andExpect(jsonPath("$.data.priority").value(3));
        mvc.perform(post("/api/dispatch/v1/orders/%s/cancel".formatted(orderNo)))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
        mvc.perform(get("/api/dispatch/v1/orders").param("status", "CANCELLED"))
                .andExpect(jsonPath("$.data[0].orderNo").value(orderNo));
        mvc.perform(post("/api/dispatch/v1/orders/%s/cancel".formatted(orderNo)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_STATE_INVALID"));
    }

    @Test
    void transportFailsCleanlyWhenTheResourceServiceIsDown() throws Exception {
        mvc.perform(post("/api/dispatch/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"type": "TRANSPORT", "carrierCode": "C1", "targetLocation": "L2"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.reason").value(org.hamcrest.Matchers.containsString("unreachable")));
    }

    @Test
    void invalidRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/dispatch/v1/orders").contentType(MediaType.APPLICATION_JSON).content("{\"priority\": 3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.type").exists());
        mvc.perform(post("/api/dispatch/v1/orders").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\": \"CHARGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ORDER"));
        mvc.perform(get("/api/dispatch/v1/orders/NOPE")).andExpect(status().isNotFound());
    }

    @Test
    void statisticsCountFinishedOrdersPerHour() throws Exception {
        MybatisOrderRepository repository = new MybatisOrderRepository(orderMapper, json);
        Instant now = Instant.now();
        List<Leg> legs = List.of(new Leg(Leg.Kind.MOVE, "P1", null, Map.of(), Leg.Status.DONE));
        repository.insert(new TransportOrder(null, "ST-1", null, OrderType.MOVE, 5, null, null, null, null, "WH1", null,
                null, OrderStatus.COMPLETED, 0, legs, null, null, now.minusSeconds(100), now, now, 0));
        repository.insert(new TransportOrder(null, "ST-2", null, OrderType.MOVE, 5, null, null, null, null, "WH1", null,
                null, OrderStatus.COMPLETED, 0, legs, null, null, now.minusSeconds(50), now, now, 0));
        repository.insert(new TransportOrder(null, "ST-3", null, OrderType.MOVE, 5, null, null, null, null, "WH1", null,
                null, OrderStatus.FAILED, 0, legs, "x", null, now.minusSeconds(10), now, now, 0));
        repository.insert(new TransportOrder(null, "ST-4", null, OrderType.CHARGE, 5, null, null, null, null, "WH1", null,
                null, OrderStatus.COMPLETED, 0, legs, null, null, now.minusSeconds(900), now, now, 0));

        mvc.perform(get("/api/dispatch/v1/statistics").param("hours", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hours.length()").value(3))
                .andExpect(jsonPath("$.data.hours[2].completed").value(2))
                .andExpect(jsonPath("$.data.hours[2].failed").value(1))
                .andExpect(jsonPath("$.data.hours[2].avgSeconds").value(75.0))
                .andExpect(jsonPath("$.data.hours[0].completed").value(0))
                .andExpect(jsonPath("$.data.total.completed").value(2))
                .andExpect(jsonPath("$.data.open.QUEUED").isNumber());
    }

    @Test
    void parametersChangedAtRuntimeAreStored() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPriority\": 7, \"parkAfterIdleSeconds\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultPriority").value(7));
        mvc.perform(get("/api/dispatch/v1/settings"))
                .andExpect(jsonPath("$.data.defaultPriority").value(7))
                .andExpect(jsonPath("$.data.parkAfterIdleSeconds").value(0));
        org.assertj.core.api.Assertions.assertThat(settingStore.load())
                .containsEntry("defaultPriority", "7").containsEntry("parkAfterIdleSeconds", "0");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPriority\": 10, \"parkAfterIdleSeconds\": 0}"))
                .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/settings")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultPriority\": 5, \"parkAfterIdleSeconds\": 30}"))
                .andExpect(status().isOk());
    }

    @Test
    void templatesAreSeededEditableAndTheStandardOneStays() throws Exception {
        mvc.perform(get("/api/dispatch/v1/templates"))
                .andExpect(jsonPath("$.data[?(@.code == 'STANDARD')].pickAction").value("pick"))
                .andExpect(jsonPath("$.data[?(@.code == 'LIFT')].dropAction").value("lower"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/templates/WEIGH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Weigh\", \"pickAction\": \"pick\", \"dropAction\": \"drop\", \"viaPoints\": [\"P5\"], \"viaAction\": \"detectObject\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.viaPoints[0]").value("P5"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/templates/WEIGH")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Weigh\", \"pickAction\": \"pick\", \"dropAction\": \"drop\"}"))
                .andExpect(jsonPath("$.data.viaPoints.length()").value(0))
                .andExpect(jsonPath("$.data.viaAction").doesNotExist());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/dispatch/v1/templates/bad code")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\": \"x\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/dispatch/v1/templates/STANDARD"))
                .andExpect(status().isConflict());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/dispatch/v1/templates/WEIGH"))
                .andExpect(status().isOk());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/dispatch/v1/templates/WEIGH"))
                .andExpect(status().isNotFound());
    }
}
