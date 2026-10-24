package org.fleetloom.resource.system;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.alarm.AlarmChangedEvent;
import org.fleetloom.contracts.fleet.ConnectionStatus;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.resource.IntegrationTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/** Alarms raised from bus events, stored in PostgreSQL, announced on the bus and handled over the API. */
class AlarmIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1/alarms";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EventBus events;

    @Test
    void offlineVehicleRaisesAnAlarmThatCanBeAcknowledged() throws Exception {
        List<AlarmChangedEvent> announced = new CopyOnWriteArrayList<>();
        events.subscribe(AlarmChangedEvent.TYPE, "alarm-test", AlarmChangedEvent.class, e -> announced.add(e.payload()));

        events.publish(VehicleConnectionChangedEvent.TYPE, new VehicleConnectionChangedEvent("AGV-IT",
                ConnectionStatus.ONLINE, ConnectionStatus.CONNECTION_BROKEN, "heartbeat timeout", Instant.now()));

        await().atMost(Duration.ofSeconds(10)).until(() -> !announced.isEmpty());
        long id = announced.getFirst().alarm().id();

        mvc.perform(get(API).param("open", "true").param("vehicle", "AGV-IT"))
                .andExpect(jsonPath("$.data[0].category").value("VEHICLE_OFFLINE"))
                .andExpect(jsonPath("$.data[0].status").value("ACTIVE"));
        mvc.perform(post(API + "/" + id + "/acknowledge").header("X-User-Name", "op1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.data.acknowledgedBy").value("op1"));

        events.publish(VehicleConnectionChangedEvent.TYPE, new VehicleConnectionChangedEvent("AGV-IT",
                ConnectionStatus.CONNECTION_BROKEN, ConnectionStatus.ONLINE, null, Instant.now()));
        await().atMost(Duration.ofSeconds(10)).until(() ->
                announced.stream().anyMatch(e -> e.alarm().id() == id && "CLEARED".equals(e.alarm().status())));
        mvc.perform(get(API).param("open", "false").param("vehicle", "AGV-IT"))
                .andExpect(jsonPath("$.data[0].id").value(id));
    }

    @Test
    void unknownAlarmIsNotFound() throws Exception {
        mvc.perform(post(API + "/987654/close")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ALARM_NOT_FOUND"));
        mvc.perform(get(API + "/summary")).andExpect(status().isOk()).andExpect(jsonPath("$.data.open").isNumber());
    }
}
