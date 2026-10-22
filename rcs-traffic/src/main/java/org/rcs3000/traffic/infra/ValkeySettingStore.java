package org.rcs3000.traffic.infra;

import java.util.LinkedHashMap;
import java.util.Map;

import org.rcs3000.traffic.app.SettingStore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Keeps the parameters in the Valkey hash {@value #KEY}. */
@Component
public class ValkeySettingStore implements SettingStore {

    public static final String KEY = "rcs:traffic:settings";

    private final StringRedisTemplate redis;

    public ValkeySettingStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Map<String, String> load() {
        Map<String, String> values = new LinkedHashMap<>();
        redis.<String, String>opsForHash().entries(KEY).forEach(values::put);
        return values;
    }

    @Override
    public void save(Map<String, String> values) {
        redis.opsForHash().putAll(KEY, values);
    }
}
