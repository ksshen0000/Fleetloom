package org.fleetloom.fleet.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.web.ConstraintViolationHandler;
import org.fleetloom.common.web.GlobalExceptionHandler;
import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.fleet.app.FleetService;
import org.fleetloom.fleet.core.FleetErrorCode;
import org.fleetloom.fleet.core.MessageLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(FleetController.class)
@Import({GlobalExceptionHandler.class, ConstraintViolationHandler.class})
class FleetControllerTest {

    private static final String BASE = "/api/fleet/v1/vehicles";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FleetService fleet;

    @MockitoBean
    private MessageLog messages;

    @Test
    void messagesAreQueriedByVehicleAndTime() throws Exception {
        Instant from = Instant.parse("2026-10-18T08:00:00Z");
        when(messages.find("AGV-1", from, null, 50)).thenReturn(List.of(new MessageLog.Entry("1-0",
                from.plusSeconds(1), "AGV-1", MessageLog.Direction.OUT, "order", "{}")));

        mvc.perform(get(BASE + "/AGV-1/messages").param("from", from.toString()).param("limit", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].direction").value("OUT"))
                .andExpect(jsonPath("$.data[0].topic").value("order"));
        mvc.perform(get(BASE + "/AGV-1/messages").param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void routeCommandIsPassedThrough() throws Exception {
        when(fleet.sendRoute(eq("AGV-1"), any())).thenReturn(new OrderRefDto("AGV-1", "AGV-1-abc", 0));

        mvc.perform(post(BASE + "/AGV-1/orders").contentType(MediaType.APPLICATION_JSON).content("""
                        {"mapCode": "WH1",
                         "nodes": [{"pointCode": "A", "x": 0, "y": 0, "released": true},
                                   {"pointCode": "B", "x": 5, "y": 0, "released": true,
                                    "actions": [{"actionType": "pick", "blocking": "HARD", "parameters": {"loadId": "P1"}}]}],
                         "edges": [{"pathCode": "AB", "released": true}]}
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value("AGV-1-abc"));
    }

    @Test
    void offlineVehicleIsAConflict() throws Exception {
        when(fleet.pause("AGV-1")).thenThrow(new BizException(FleetErrorCode.VEHICLE_NOT_ONLINE, "vehicle AGV-1 is OFFLINE"));

        mvc.perform(post(BASE + "/AGV-1/pause"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_ONLINE"));
    }

    @Test
    void relocateRequiresCoordinates() throws Exception {
        mvc.perform(post(BASE + "/AGV-1/relocate").contentType(MediaType.APPLICATION_JSON).content("{\"x\": 1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.y").exists());

        mvc.perform(post(BASE + "/AGV-1/relocate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"x\": 1, \"y\": 2, \"mapCode\": \"WH1\"}"))
                .andExpect(status().isOk());
        verify(fleet).relocate("AGV-1", 1, 2, 0, "WH1", null);
    }

    @Test
    void unknownVehicleIsNotFound() throws Exception {
        when(fleet.vehicle("X")).thenThrow(new BizException(FleetErrorCode.VEHICLE_NOT_FOUND, "vehicle X is unknown"));

        mvc.perform(get(BASE + "/X")).andExpect(status().isNotFound());
    }
}
