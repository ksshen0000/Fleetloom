package org.rcs3000.resource.vehicle;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.rcs3000.resource.IntegrationTestBase;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

class VehicleRegistryIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1";

    @Autowired
    private MockMvc mvc;

    @Test
    void registerUpdateAndDeleteAVehicle() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6);
        mvc.perform(post(API + "/vehicle-types").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "JACK%s", "name": "Jacking AGV", "category": "JACKING", "length": 1.2, "width": 0.8,
                         "maxSpeed": 1.5, "maxLoad": 1000, "supportedActions": ["pick", "drop"]}""".formatted(s)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.supportedActions.length()").value(2));

        mvc.perform(post(API + "/vehicles").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "AGV-%s", "name": "AGV 1", "typeCode": "JACK%s", "manufacturer": "RCS3000-SIM",
                         "serialNumber": "SN-%s", "simulated": true, "initialPoint": "P00"}""".formatted(s, s, s)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.chargeThresholdLow").value(20.0))
                .andExpect(jsonPath("$.data.enabled").value(true));

        // the same physical vehicle cannot be registered twice
        mvc.perform(post(API + "/vehicles").contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "AGV-%s-2", "name": "dup", "typeCode": "JACK%s", "manufacturer": "RCS3000-SIM",
                         "serialNumber": "SN-%s"}""".formatted(s, s, s)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_IDENTITY_DUPLICATE"));

        mvc.perform(put(API + "/vehicles/AGV-" + s).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "ignored", "name": "AGV 1", "typeCode": "JACK%s", "manufacturer": "RCS3000-SIM",
                         "serialNumber": "SN-%s", "enabled": false, "chargeThresholdLow": 90, "chargeThresholdHigh": 30}"""
                        .formatted(s, s)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CHARGE_THRESHOLDS"));

        mvc.perform(put(API + "/vehicles/AGV-" + s).contentType(MediaType.APPLICATION_JSON).content("""
                        {"code": "ignored", "name": "AGV one", "typeCode": "JACK%s", "manufacturer": "RCS3000-SIM",
                         "serialNumber": "SN-%s", "enabled": false}""".formatted(s, s)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("AGV one"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.initialPoint").doesNotExist());

        mvc.perform(delete(API + "/vehicle-types/JACK" + s)).andExpect(status().isConflict());
        mvc.perform(delete(API + "/vehicles/AGV-" + s)).andExpect(status().isOk());
        mvc.perform(get(API + "/vehicles/AGV-" + s)).andExpect(status().isNotFound());
        mvc.perform(delete(API + "/vehicle-types/JACK" + s)).andExpect(status().isOk());
    }
}
