package org.fleetloom.simulator.platform;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.web.HttpClients;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.RestClient;

/** Reads the active map from fleetloom-resource. */
public class MapClient {

    private final RestClient rest;

    public MapClient(RestClient.Builder builder, String resourceUrl) {
        this.rest = HttpClients.withTimeouts(builder, resourceUrl).build();
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
