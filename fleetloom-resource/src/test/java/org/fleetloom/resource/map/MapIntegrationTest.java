package org.fleetloom.resource.map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.fleetloom.resource.IntegrationTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * End-to-end map lifecycle against a real PostgreSQL. Skipped when Docker is not available.
 */
class MapIntegrationTest extends IntegrationTestBase {

    private static final String BASE = "/api/resource/v1/maps";

    private static final String SQUARE = """
            {
              "revision": %d,
              "points": [
                {"code": "A", "x": 0,  "y": 0,  "type": "NORMAL"},
                {"code": "B", "x": 10, "y": 0,  "type": "NORMAL"},
                {"code": "C", "x": 10, "y": 10, "type": "CHARGING", "properties": {"charger": "CH-01"}}
              ],
              "paths": [
                {"code": "AB", "fromPoint": "A", "toPoint": "B", "direction": "BIDIRECTIONAL"},
                {"code": "BC", "fromPoint": "B", "toPoint": "C", "direction": "BIDIRECTIONAL",
                 "shape": "BEZIER", "controlPoints": [{"x": 12, "y": 3}, {"x": 12, "y": 7}], "maxSpeed": 0.8},
                {"code": "CA", "fromPoint": "C", "toPoint": "A", "direction": "UNIDIRECTIONAL"}
              ],
              "areas": [
                {"code": "Z1", "type": "MUTEX", "polygon": [{"x": 0, "y": 0}, {"x": 5, "y": 0}, {"x": 5, "y": 5}]}
              ]
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void fullMapLifecycle() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "IT1", "name": "Integration map"}
                        """))
                .andExpect(status().isCreated());

        mvc.perform(post(BASE + "/IT1/versions")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(1));

        mvc.perform(put(BASE + "/IT1/versions/1/content").contentType(MediaType.APPLICATION_JSON).content(SQUARE.formatted(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.revision").value(1));

        // A second editor still holding revision 0 must not overwrite the first one's work
        mvc.perform(put(BASE + "/IT1/versions/1/content").contentType(MediaType.APPLICATION_JSON).content(SQUARE.formatted(0)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MAP_REVISION_CONFLICT"));

        mvc.perform(post(BASE + "/IT1/versions/1/publish").header("X-User-Name", "alice"))
                .andExpect(status().isOk());

        // other services learn about the new version through the event bus
        var published = redis.opsForStream().range(
                org.fleetloom.common.event.valkey.ValkeyEventBus.streamKey("MapPublished"),
                org.springframework.data.domain.Range.unbounded());
        org.assertj.core.api.Assertions.assertThat(published).anySatisfy(r ->
                org.assertj.core.api.Assertions.assertThat(r.getValue().toString()).contains("IT1"));

        mvc.perform(get(BASE + "/IT1/active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNo").value(1))
                .andExpect(jsonPath("$.data.points.length()").value(3))
                .andExpect(jsonPath("$.data.points[2].properties.charger").value("CH-01"))
                .andExpect(jsonPath("$.data.paths[0].length").value(10.0))
                .andExpect(jsonPath("$.data.paths[1].maxSpeed").value(0.8))
                .andExpect(jsonPath("$.data.areas[0].polygon.length()").value(3));

        mvc.perform(post(BASE + "/IT1/versions").contentType(MediaType.APPLICATION_JSON).content("""
                        {"copyFromVersionNo": 1}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.versionNo").value(2))
                .andExpect(jsonPath("$.data.paths.length()").value(3));

        mvc.perform(post(BASE + "/IT1/versions/2/publish")).andExpect(status().isOk());

        mvc.perform(get(BASE + "/IT1/versions"))
                .andExpect(jsonPath("$.data[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data[1].status").value("ARCHIVED"))
                .andExpect(jsonPath("$.data[1].publishedBy").value("alice"));
    }

    @Test
    void publishingAnInvalidDraftReturnsTheReport() throws Exception {
        mvc.perform(post(BASE).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "IT2", "name": "Broken map"}
                        """))
                .andExpect(status().isCreated());
        mvc.perform(post(BASE + "/IT2/versions")).andExpect(status().isCreated());

        mvc.perform(post(BASE + "/IT2/versions/1/publish"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("MAP_VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data.issues[0].rule").value("EMPTY_MAP"));
    }
}
