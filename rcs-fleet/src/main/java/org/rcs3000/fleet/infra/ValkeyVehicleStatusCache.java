package org.rcs3000.fleet.infra;

import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.fleet.core.VehicleStatusCache;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Keeps the latest status of every vehicle in the Valkey hash {@value #KEY}, field = vehicle code. */
public class ValkeyVehicleStatusCache implements VehicleStatusCache {

    public static final String KEY = "rcs:fleet:vehicles";

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public ValkeyVehicleStatusCache(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void put(VehicleStatusDto status) {
        redis.opsForHash().put(KEY, status.code(), json.writeValueAsString(status));
    }
}
