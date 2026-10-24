package org.fleetloom.dispatch.app;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.fleetloom.dispatch.core.DispatchSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The dispatch parameters an administrator may change at runtime (WEB-09). Changes take effect at
 * once and are stored, so they survive restarts; the configuration only supplies the defaults.
 */
@Service
public class DispatchTuning {

    static final String DEFAULT_PRIORITY = "defaultPriority";
    static final String PARK_AFTER_IDLE = "parkAfterIdleSeconds";
    private static final Logger log = LoggerFactory.getLogger(DispatchTuning.class);

    private final DispatchEngine engine;
    private final SettingStore store;

    public DispatchTuning(DispatchEngine engine, SettingStore store) {
        this.engine = engine;
        this.store = store;
        Map<String, String> stored = store.load();
        if (!stored.isEmpty()) {
            apply(merge(current(), stored));
            log.info("dispatch parameters from the database: {}", stored);
        }
    }

    /**
     * @param defaultPriority      priority of orders that do not give one, 1 (lowest) to 9
     * @param parkAfterIdleSeconds idle vehicles go to a parking point after this long; 0 = never
     */
    public record Tunables(int defaultPriority, long parkAfterIdleSeconds) {
    }

    public Tunables get() {
        DispatchSettings s = current();
        return new Tunables(s.defaultPriority(), s.parkAfterIdle().toSeconds());
    }

    public Tunables update(Tunables t) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(DEFAULT_PRIORITY, Integer.toString(t.defaultPriority()));
        values.put(PARK_AFTER_IDLE, Long.toString(t.parkAfterIdleSeconds()));
        store.save(values);
        apply(merge(current(), values));
        log.info("dispatch parameters changed: {}", values);
        return get();
    }

    private DispatchSettings current() {
        return engine.call(d -> d.settings());
    }

    private void apply(DispatchSettings settings) {
        engine.call(d -> {
            d.updateSettings(settings);
            return null;
        });
    }

    private static DispatchSettings merge(DispatchSettings s, Map<String, String> values) {
        int priority = values.containsKey(DEFAULT_PRIORITY) ? Integer.parseInt(values.get(DEFAULT_PRIORITY))
                : s.defaultPriority();
        Duration park = values.containsKey(PARK_AFTER_IDLE)
                ? Duration.ofSeconds(Long.parseLong(values.get(PARK_AFTER_IDLE))) : s.parkAfterIdle();
        return new DispatchSettings(s.routePollInterval(), s.registryRefresh(), priority, park);
    }
}
