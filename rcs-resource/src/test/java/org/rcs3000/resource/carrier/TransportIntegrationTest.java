package org.rcs3000.resource.carrier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.IntegrationTestBase;
import org.rcs3000.resource.carrier.application.TransportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Storage locations, carriers and transports through the REST API on a real database. */
class TransportIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TransportService transports;

    /** Unique suffix so test methods do not see each other's data. */
    private String s;

    @BeforeEach
    void setUp() throws Exception {
        s = UUID.randomUUID().toString().substring(0, 6);
        post("/zones", """
                {"code": "Z%s", "name": "Zone", "type": "RAW"}""".formatted(s)).andExpect(status().isCreated());
        post("/carrier-types", """
                {"code": "PAL%s", "name": "Pallet", "category": "PALLET", "length": 1.2, "width": 0.8, "height": 0.15,
                 "maxLoad": 1000}""".formatted(s)).andExpect(status().isCreated());
        post("/carrier-types", """
                {"code": "SHF%s", "name": "Shelf", "category": "SHELF", "length": 1, "width": 1, "height": 2,
                 "maxLoad": 500}""".formatted(s)).andExpect(status().isCreated());
        for (int i = 1; i <= 4; i++) {
            post("/storage-locations", """
                    {"code": "L%d-%s", "zoneCode": "Z%s", "type": "GROUND", "mapCode": "M%s", "accessPoint": "P%d"}"""
                    .formatted(i, s, s, s, i)).andExpect(status().isCreated());
        }
        for (int i = 1; i <= 2; i++) {
            post("/carriers", """
                    {"code": "C%d-%s", "typeCode": "PAL%s"}""".formatted(i, s, s)).andExpect(status().isCreated());
            post("/carriers/C%d-%s/place".formatted(i, s), """
                    {"locationCode": "L%d-%s"}""".formatted(i, s)).andExpect(status().isOk());
        }
    }

    @Test
    void carrierIsMovedFromOneLocationToAnother() throws Exception {
        post("/transports/locks", """
                {"owner": "O1", "carrierCode": "C1-%s", "targetLocation": "L3-%s"}""".formatted(s, s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sourceLocation").value("L1-" + s))
                .andExpect(jsonPath("$.data.sourcePoint").value("P1"))
                .andExpect(jsonPath("$.data.targetPoint").value("P3"))
                .andExpect(jsonPath("$.data.carrierType").value("PAL" + s));
        expectLocation("L1", "LOCKED_OUT", "C1");
        expectLocation("L3", "LOCKED_IN", null);

        post("/transports/picked-up", """
                {"owner": "WRONG", "carrierCode": "C1-%s", "vehicleCode": "AGV-1"}""".formatted(s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCK_OWNER_MISMATCH"));

        post("/transports/picked-up", """
                {"owner": "O1", "carrierCode": "C1-%s", "vehicleCode": "AGV-1"}""".formatted(s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationType").value("VEHICLE"))
                .andExpect(jsonPath("$.data.status").value("IN_TRANSIT"));
        expectLocation("L1", "EMPTY", null);

        post("/transports/dropped-off", """
                {"owner": "O1", "carrierCode": "C1-%s", "targetLocation": "L3-%s"}""".formatted(s, s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.locationRef").value("L3-" + s));
        expectLocation("L3", "OCCUPIED", "C1");

        mvc.perform(get(API + "/carriers/C1-%s/movements".formatted(s)))
                .andExpect(jsonPath("$.data.length()").value(3))
                .andExpect(jsonPath("$.data[0].toRef").value("L3-" + s))
                .andExpect(jsonPath("$.data[0].orderRef").value("O1"))
                .andExpect(jsonPath("$.data[1].toType").value("VEHICLE"))
                .andExpect(jsonPath("$.data[2].orderRef").doesNotExist());
    }

    @Test
    void failedLockLeavesBothEndsUntouched() throws Exception {
        post("/transports/locks", """
                {"owner": "O1", "carrierCode": "C1-%s", "targetLocation": "L3-%s"}""".formatted(s, s)).andExpect(status().isOk());

        // L3 is taken, so the source of C2 must not stay locked either
        post("/transports/locks", """
                {"owner": "O2", "carrierCode": "C2-%s", "targetLocation": "L3-%s"}""".formatted(s, s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCATION_STATE_INVALID"));
        expectLocation("L2", "OCCUPIED", "C2");
    }

    @Test
    void targetMustAcceptTheCarrierType() throws Exception {
        put("/storage-locations/L4-%s".formatted(s), """
                {"code": "L4-%s", "zoneCode": "Z%s", "type": "RACK", "mapCode": "M%s", "accessPoint": "P4",
                 "allowedCarrierTypes": ["SHF%s"]}""".formatted(s, s, s, s)).andExpect(status().isOk());

        post("/transports/locks", """
                {"owner": "O1", "carrierCode": "C1-%s", "targetLocation": "L4-%s"}""".formatted(s, s))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CARRIER_TYPE_NOT_ALLOWED"));
        expectLocation("L1", "OCCUPIED", "C1");
    }

    @Test
    void releaseUndoesTheLocksOfOneOwnerOnly() throws Exception {
        post("/transports/locks", """
                {"owner": "O1", "carrierCode": "C1-%s", "targetLocation": "L3-%s"}""".formatted(s, s)).andExpect(status().isOk());
        post("/transports/locks", """
                {"owner": "O2", "carrierCode": "C2-%s", "targetLocation": "L4-%s"}""".formatted(s, s)).andExpect(status().isOk());

        post("/transports/release", """
                {"owner": "O1", "locations": ["L1-%s", "L3-%s", "L4-%s"]}""".formatted(s, s, s))
                .andExpect(jsonPath("$.data.length()").value(2));

        expectLocation("L1", "OCCUPIED", "C1");
        expectLocation("L3", "EMPTY", null);
        expectLocation("L4", "LOCKED_IN", null);
    }

    /** Two orders race for the same empty target: exactly one gets it, the other changes nothing. */
    @Test
    void concurrentLocksOnOneTargetAdmitExactlyOne() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> attempts = new ArrayList<>();
        for (int i = 1; i <= 2; i++) {
            String owner = "O" + i;
            String carrier = "C" + i + "-" + s;
            attempts.add(() -> {
                try {
                    transports.lock(owner, carrier, "L3-" + s);
                    return owner;
                } catch (BizException e) {
                    return e.getErrorCode().code();
                }
            });
        }
        List<String> results = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(attempts)) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn(r -> r.startsWith("O")).hasSize(1);
        assertThat(results).filteredOn(r -> !r.startsWith("O"))
                .singleElement().isIn("STORAGE_CONCURRENT_MODIFICATION", "LOCATION_STATE_INVALID");
        String winner = results.stream().filter(r -> r.startsWith("O")).findFirst().orElseThrow();
        String loserSource = winner.equals("O1") ? "L2" : "L1";
        expectLocation(loserSource, "OCCUPIED", loserSource.equals("L1") ? "C1" : "C2");
    }

    @Test
    void availableLocationsMatchZoneAndCarrierType() throws Exception {
        put("/storage-locations/L4-%s".formatted(s), """
                {"code": "L4-%s", "zoneCode": "Z%s", "type": "RACK", "mapCode": "M%s", "accessPoint": "P4",
                 "allowedCarrierTypes": ["SHF%s"]}""".formatted(s, s, s, s)).andExpect(status().isOk());

        mvc.perform(get(API + "/storage-locations/available").param("zone", "Z" + s).param("carrierType", "PAL" + s))
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("L3-" + s));
        mvc.perform(get(API + "/storage-locations/available").param("zone", "Z" + s).param("carrierType", "SHF" + s))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void nearestAvailableLocationOnAPublishedMap() throws Exception {
        String map = "N" + s;
        post("/maps", """
                {"code": "%s", "name": "near"}""".formatted(map)).andExpect(status().isCreated());
        post("/maps/%s/versions".formatted(map), "{}").andExpect(status().isCreated());
        put("/maps/%s/versions/1/content".formatted(map), """
                {"revision": 0,
                 "points": [{"code": "A", "x": 0, "y": 0, "type": "NORMAL"}, {"code": "B", "x": 10, "y": 0, "type": "NORMAL"},
                            {"code": "C", "x": 3, "y": 0, "type": "NORMAL"}],
                 "paths": [{"code": "AC", "fromPoint": "A", "toPoint": "C", "direction": "BIDIRECTIONAL"},
                           {"code": "CB", "fromPoint": "C", "toPoint": "B", "direction": "BIDIRECTIONAL"}]}""")
                .andExpect(status().isOk());
        post("/maps/%s/versions/1/publish".formatted(map), "").andExpect(status().isOk());
        post("/storage-locations", """
                {"code": "FAR-%s", "zoneCode": "Z%s", "type": "GROUND", "mapCode": "%s", "accessPoint": "B"}"""
                .formatted(s, s, map)).andExpect(status().isCreated());
        post("/storage-locations", """
                {"code": "NEAR-%s", "zoneCode": "Z%s", "type": "GROUND", "mapCode": "%s", "accessPoint": "C"}"""
                .formatted(s, s, map)).andExpect(status().isCreated());

        mvc.perform(get(API + "/storage-locations/available").param("map", map).param("nearPoint", "A"))
                .andExpect(jsonPath("$.data[0].code").value("NEAR-" + s))
                .andExpect(jsonPath("$.data[0].distance").value(3.0))
                .andExpect(jsonPath("$.data[1].code").value("FAR-" + s));

        post("/storage-locations", """
                {"code": "BAD-%s", "zoneCode": "Z%s", "type": "GROUND", "mapCode": "%s", "accessPoint": "NOPE"}"""
                .formatted(s, s, map))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("ACCESS_POINT_UNKNOWN"));
    }

    @Test
    void referencedObjectsCannotBeDeleted() throws Exception {
        mvc.perform(delete(API + "/zones/Z" + s)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ZONE_IN_USE"));
        mvc.perform(delete(API + "/carrier-types/PAL" + s)).andExpect(status().isConflict());
        mvc.perform(delete(API + "/carriers/C1-" + s)).andExpect(status().isConflict());
        mvc.perform(delete(API + "/storage-locations/L1-" + s)).andExpect(status().isConflict());

        post("/carriers/C1-%s/take-out".formatted(s), "").andExpect(status().isOk());
        expectLocation("L1", "EMPTY", null);
        mvc.perform(delete(API + "/carriers/C1-" + s)).andExpect(status().isOk());
        mvc.perform(delete(API + "/storage-locations/L1-" + s)).andExpect(status().isOk());
    }

    @Test
    void disabledLocationIsNotAvailable() throws Exception {
        post("/storage-locations/L3-%s/disable".formatted(s), "").andExpect(jsonPath("$.data.status").value("DISABLED"));

        mvc.perform(get(API + "/storage-locations/available").param("zone", "Z" + s))
                .andExpect(jsonPath("$.data[*].code").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("L3-" + s))));
        post("/storage-locations/L3-%s/enable".formatted(s), "").andExpect(jsonPath("$.data.status").value("EMPTY"));
    }

    private void expectLocation(String location, String status, String carrier) throws Exception {
        mvc.perform(get(API + "/storage-locations/" + location + "-" + s))
                .andExpect(jsonPath("$.data.status").value(status))
                .andExpect(carrier == null
                        ? jsonPath("$.data.carrierCode").doesNotExist()
                        : jsonPath("$.data.carrierCode").value(carrier + "-" + s));
    }

    private ResultActions post(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(API + path)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions put(String path, String body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(API + path)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
