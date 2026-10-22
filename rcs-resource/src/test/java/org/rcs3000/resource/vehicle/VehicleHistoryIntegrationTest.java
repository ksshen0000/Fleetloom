package org.rcs3000.resource.vehicle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.event.EventBus;
import org.rcs3000.contracts.fleet.ConnectionStatus;
import org.rcs3000.contracts.fleet.VehicleStateUpdatedEvent;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.resource.IntegrationTestBase;
import org.rcs3000.resource.vehicle.application.VehicleHistory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

class VehicleHistoryIntegrationTest extends IntegrationTestBase {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EventBus events;

    @Autowired
    private VehicleHistory history;

    @Test
    void reportedStatesBecomeAHistory() throws Exception {
        Instant now = Instant.now();
        events.publish(VehicleStateUpdatedEvent.TYPE, state("HIST-1", now.minusSeconds(10), 0, 95, "P00"));
        events.publish(VehicleStateUpdatedEvent.TYPE, state("HIST-1", now.minusSeconds(9), 0.1, 95, "P00"));
        events.publish(VehicleStateUpdatedEvent.TYPE, state("HIST-1", now.minusSeconds(5), 4, 94, "P01"));

        await().atMost(Duration.ofSeconds(10)).until(() -> history.find("HIST-1", null, null, 100).size() == 2);

        mvc.perform(get("/api/resource/v1/vehicles/HIST-1/history"))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].lastNodeId").value("P00"))
                .andExpect(jsonPath("$.data[1].x").value(4.0))
                .andExpect(jsonPath("$.data[1].battery").value(94.0));
        mvc.perform(get("/api/resource/v1/vehicles/HIST-1/history").param("from", now.minusSeconds(7).toString()))
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void oldSamplesArePurged() {
        history.record(state("HIST-2", Instant.now().minus(Duration.ofDays(30)), 0, 50, "P00").status());
        history.purge();

        assertThat(history.find("HIST-2", Instant.now().minus(Duration.ofDays(60)), null, 10)).isEmpty();
    }

    private static VehicleStateUpdatedEvent state(String code, Instant at, double x, double battery, String node) {
        return new VehicleStateUpdatedEvent(new VehicleStatusDto(code, "SIM", code, ConnectionStatus.ONLINE, at, "DEMO",
                x, 0, 0, true, 0, false, false, "AUTOMATIC", null, 0, node, 0, List.of(), battery, false, List.of(),
                List.of(), List.of()));
    }
}
