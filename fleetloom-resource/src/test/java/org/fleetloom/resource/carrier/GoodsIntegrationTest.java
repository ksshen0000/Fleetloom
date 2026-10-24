package org.fleetloom.resource.carrier;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

class GoodsIntegrationTest extends IntegrationTestBase {

    private static final String API = "/api/resource/v1";

    @Autowired
    private MockMvc mvc;

    @Test
    void goodsAreRecordedPerCarrierAndFoundBySku() throws Exception {
        String s = Long.toString(System.nanoTime() % 1_000_000);
        json(post(API + "/carrier-types"), """
                {"code": "GT%s", "name": "Tote", "category": "BIN", "length": 0.6, "width": 0.4, "height": 0.3, "maxLoad": 30}
                """.formatted(s)).andExpect(status().isCreated());
        json(post(API + "/carriers"), """
                {"code": "G1-%s", "typeCode": "GT%s"}""".formatted(s, s)).andExpect(status().isCreated());

        json(put(API + "/carriers/G1-%s/goods".formatted(s)), """
                [{"sku": "SKU-%s-A", "name": "Bolt M8", "quantity": 120, "unit": "pcs", "batchNo": "B7"},
                 {"sku": "SKU-%s-B", "quantity": 2.5, "unit": "kg"}]""".formatted(s, s))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        mvc.perform(get(API + "/carriers/G1-%s/goods".formatted(s)))
                .andExpect(jsonPath("$.data[0].name").value("Bolt M8"))
                .andExpect(jsonPath("$.data[1].quantity").value(2.5));
        mvc.perform(get(API + "/goods").param("sku", "SKU-%s".formatted(s)))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].carrierCode").value("G1-" + s))
                .andExpect(jsonPath("$.data[0].locationType").value("NONE"));
        // _ and % in the search are taken literally
        mvc.perform(get(API + "/goods").param("sku", "SKU_"))
                .andExpect(jsonPath("$.data.length()").value(0));

        json(put(API + "/carriers/G1-%s/goods".formatted(s)), """
                [{"sku": "", "quantity": 1}]""").andExpect(status().isBadRequest());
        json(put(API + "/carriers/G1-%s/goods".formatted(s)), """
                [{"sku": "X", "quantity": -1}]""").andExpect(status().isBadRequest());
        json(put(API + "/carriers/NOPE-%s/goods".formatted(s)), "[]").andExpect(status().isNotFound());

        json(put(API + "/carriers/G1-%s/goods".formatted(s)), "[]").andExpect(jsonPath("$.data.length()").value(0));
        json(put(API + "/carriers/G1-%s/goods".formatted(s)), """
                [{"sku": "SKU-%s-C", "quantity": 1}]""".formatted(s)).andExpect(status().isOk());
        mvc.perform(delete(API + "/carriers/G1-" + s)).andExpect(status().isOk());
        mvc.perform(get(API + "/goods").param("sku", "SKU-%s".formatted(s)))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    private org.springframework.test.web.servlet.ResultActions json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
