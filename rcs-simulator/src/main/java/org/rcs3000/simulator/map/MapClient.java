package org.rcs3000.simulator.map;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Reads the active map from rcs-resource. */
public class MapClient {

    private final RestClient rest;

    public MapClient(RestClient.Builder builder, String resourceUrl) {
        this.rest = builder.baseUrl(resourceUrl).build();
    }

    public MapTopologyDto activeTopology(String mapCode) {
        ApiResponse<MapTopologyDto> response = rest.get()
                .uri("/api/resource/v1/maps/{code}/active", mapCode)
                .retrieve()
                .body(new ParameterizedTypeReference<ApiResponse<MapTopologyDto>>() { });
        if (response == null || response.data() == null) {
            throw new IllegalStateException("map service returned no topology for " + mapCode);
        }
        return response.data();
    }
}
