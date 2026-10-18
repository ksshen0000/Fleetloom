package org.rcs3000.traffic.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.common.web.GlobalExceptionHandler;
import org.rcs3000.traffic.app.TrafficEngine;
import org.rcs3000.traffic.core.TrafficErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TrafficApiController.class)
@Import(GlobalExceptionHandler.class)
class TrafficApiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private TrafficEngine engine;

    @Test
    void routeRequestNeedsVehicleAndTarget() throws Exception {
        mvc.perform(post("/api/traffic/v1/routes").contentType(MediaType.APPLICATION_JSON).content("{\"vehicleCode\": \"AGV-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.targetPoint").exists());
    }

    @Test
    @SuppressWarnings("unchecked")
    void controllerErrorsMapToHttpStatus() throws Exception {
        when(engine.call(any(Function.class))).thenThrow(
                new BizException(TrafficErrorCode.NO_ROUTE, "no route from P00 to P99"));

        mvc.perform(post("/api/traffic/v1/routes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vehicleCode\": \"AGV-1\", \"targetPoint\": \"P99\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("NO_ROUTE"));
    }

    @Test
    void costQueryNeedsPoints() throws Exception {
        mvc.perform(post("/api/traffic/v1/costs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mapCode\": \"WH1\", \"from\": [], \"to\": [\"A\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingRouteIsAConflict() throws Exception {
        when(engine.call(any(Function.class))).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/api/traffic/v1/routes/AGV-1")).andExpect(status().isConflict());
    }
}
