package org.fleetloom.simulator.scenario;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.fleetloom.common.api.ApiResponse;
import org.fleetloom.common.web.HttpClients;
import org.fleetloom.contracts.dispatch.CreateOrderDto;
import org.fleetloom.contracts.dispatch.OrderViewDto;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/** Calls fleetloom-dispatch and fleetloom-resource directly, the way other services do (not through the gateway). */
public class HttpPlatform implements Platform {

    private static final ParameterizedTypeReference<ApiResponse<OrderViewDto>> ORDER = new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<List<Location>>> LOCATIONS =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<ApiResponse<List<Stocked>>> STOCKED =
            new ParameterizedTypeReference<>() { };

    private final RestClient dispatch;
    private final RestClient resource;

    public HttpPlatform(RestClient.Builder builder, String dispatchUrl, String resourceUrl) {
        this.dispatch = HttpClients.withTimeouts(builder.clone(), dispatchUrl).build();
        this.resource = HttpClients.withTimeouts(builder.clone(), resourceUrl).build();
    }

    @Override
    public OrderViewDto createOrder(CreateOrderDto order) {
        return data(dispatch.post().uri("/api/dispatch/v1/orders").body(order).retrieve().body(ORDER));
    }

    @Override
    public OrderViewDto order(String orderNo) {
        return data(dispatch.get().uri("/api/dispatch/v1/orders/{no}", orderNo).retrieve().body(ORDER));
    }

    @Override
    public void registerVehicle(String code, String typeCode, String manufacturer, String initialPoint) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", code);
        body.put("typeCode", typeCode);
        body.put("manufacturer", manufacturer);
        body.put("serialNumber", code);
        body.put("simulated", true);
        body.put("initialPoint", initialPoint);
        try {
            resource.post().uri("/api/resource/v1/vehicles").body(body).retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() != HttpStatus.CONFLICT) {
                throw e;
            }
        }
    }

    @Override
    public List<Location> locations(String zone) {
        return data(resource.get().uri("/api/resource/v1/storage-locations?zone={zone}", zone).retrieve().body(LOCATIONS));
    }

    @Override
    public List<String> carriersWithGoods(String sku) {
        return data(resource.get().uri("/api/resource/v1/goods?sku={sku}&limit=1000", sku).retrieve().body(STOCKED))
                .stream().map(Stocked::carrierCode).distinct().toList();
    }

    @Override
    public void createCarrier(String code, String typeCode, String location, List<GoodsLine> goods) {
        resource.post().uri("/api/resource/v1/carriers").body(Map.of("code", code, "typeCode", typeCode))
                .retrieve().toBodilessEntity();
        try {
            resource.post().uri("/api/resource/v1/carriers/{code}/place", code).body(Map.of("locationCode", location))
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException e) {
            resource.delete().uri("/api/resource/v1/carriers/{code}", code).retrieve().toBodilessEntity();
            throw e;
        }
        if (!goods.isEmpty()) {
            resource.put().uri("/api/resource/v1/carriers/{code}/goods", code)
                    .body(goods.stream().map(g -> Map.of("sku", g.sku(), "quantity", g.quantity())).toList())
                    .retrieve().toBodilessEntity();
        }
    }

    @Override
    public void removeCarrier(String code) {
        resource.post().uri("/api/resource/v1/carriers/{code}/take-out", code).retrieve().toBodilessEntity();
        resource.delete().uri("/api/resource/v1/carriers/{code}", code).retrieve().toBodilessEntity();
    }

    private record Stocked(String carrierCode) {
    }

    private static <T> T data(ApiResponse<T> response) {
        if (response == null || response.data() == null) {
            throw new IllegalStateException("empty response");
        }
        return response.data();
    }
}
