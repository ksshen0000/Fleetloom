package org.fleetloom.resource.map.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.fleetloom.common.error.BizException;
import org.fleetloom.common.web.GlobalExceptionHandler;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.VehicleOrientation;
import org.fleetloom.resource.map.application.MapApplicationService;
import org.fleetloom.resource.map.application.MapView;
import org.fleetloom.resource.map.application.SaveDraftResult;
import org.fleetloom.resource.map.domain.MapContent;
import org.fleetloom.resource.map.domain.MapErrorCode;
import org.fleetloom.resource.map.domain.MapPath;
import org.fleetloom.resource.map.domain.ValidationReport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MapController.class)
@Import(GlobalExceptionHandler.class)
class MapControllerTest {

    private static final String BASE = "/api/resource/v1/maps";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private MapApplicationService service;

    @Test
    void createMapReturns201WithEnvelope() throws Exception {
        Instant now = Instant.parse("2026-10-18T08:00:00Z");
        when(service.createMap("WH1", "Warehouse 1", null)).thenReturn(new MapView("WH1", "Warehouse 1", null, null, now, now));

        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "WH1", "name": "Warehouse 1"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.code").value("WH1"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-18T08:00:00Z"));
    }

    @Test
    void invalidMapCodeIsRejectedBeforeReachingTheService() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "has space", "name": "x"}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.code").exists());
    }

    @Test
    void businessErrorMapsToStatusAndCode() throws Exception {
        when(service.getMap("NOPE")).thenThrow(new BizException(MapErrorCode.MAP_NOT_FOUND, "map 'NOPE' not found"));

        mvc.perform(get(BASE + "/NOPE"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MAP_NOT_FOUND"));
    }

    @Test
    void saveContentAppliesDefaultsForOmittedPathFields() throws Exception {
        when(service.saveDraftContent(eq("WH1"), eq(1), any(), eq(3)))
                .thenReturn(new SaveDraftResult(4, new ValidationReport(List.of())));

        mvc.perform(put(BASE + "/WH1/versions/1/content").contentType(MediaType.APPLICATION_JSON).content("""
                        {
                          "revision": 3,
                          "points": [
                            {"code": "A", "x": 0, "y": 0, "type": "NORMAL"},
                            {"code": "B", "x": 10, "y": 0, "type": "CHARGING"}
                          ],
                          "paths": [
                            {"code": "AB", "fromPoint": "A", "toPoint": "B", "direction": "BIDIRECTIONAL"}
                          ]
                        }
                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(4))
                .andExpect(jsonPath("$.data.validation.issues").isEmpty());

        ArgumentCaptor<MapContent> content = ArgumentCaptor.forClass(MapContent.class);
        verify(service).saveDraftContent(eq("WH1"), eq(1), content.capture(), eq(3));
        MapPath path = content.getValue().paths().getFirst();
        org.assertj.core.api.Assertions.assertThat(path)
                .extracting(MapPath::direction, MapPath::shape, MapPath::orientation, MapPath::weightFactor, MapPath::enabled)
                .containsExactly(PathDirection.BIDIRECTIONAL, PathShape.LINE, VehicleOrientation.ANY, 1.0, true);
    }

    @Test
    void saveContentRequiresRevision() throws Exception {
        mvc.perform(put(BASE + "/WH1/versions/1/content").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.revision").exists());
    }

    @Test
    void publishPassesTheUserFromTheGatewayHeader() throws Exception {
        when(service.publish("WH1", 2, "alice")).thenReturn(new ValidationReport(List.of()));

        mvc.perform(post(BASE + "/WH1/versions/2/publish").header(MapController.USER_HEADER, "alice"))
                .andExpect(status().isOk());

        verify(service).publish("WH1", 2, "alice");
    }

    @Test
    void createDraftWithoutBodyStartsEmpty() throws Exception {
        mvc.perform(post(BASE + "/WH1/versions")).andExpect(status().isCreated());

        verify(service).createDraft("WH1", null);
    }
}
