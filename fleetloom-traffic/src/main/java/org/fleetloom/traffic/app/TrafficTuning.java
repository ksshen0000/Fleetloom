package org.fleetloom.traffic.app;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.fleetloom.traffic.core.TrafficSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The traffic parameters an administrator may change at runtime (WEB-09). Changes apply to the next
 * reservation step and are stored, so they survive restarts; the configuration only supplies the
 * defaults.
 */
@Service
public class TrafficTuning {

    private static final Logger log = LoggerFactory.getLogger(TrafficTuning.class);

    private final TrafficEngine engine;
    private final SettingStore store;

    public TrafficTuning(TrafficEngine engine, SettingStore store) {
        this.engine = engine;
        this.store = store;
        Map<String, String> stored = store.load();
        if (!stored.isEmpty()) {
            apply(merge(current(), stored));
            log.info("traffic parameters from storage: {}", stored);
        }
    }

    /**
     * @param lookaheadNodes            reserve at least this many points ahead of a vehicle
     * @param lookaheadDistance         and at least this many meters
     * @param occupiedPenalty           extra planning seconds for a resource another vehicle holds
     * @param blockedEventAfterSeconds  a waiting vehicle is reported as blocked after this long
     * @param replanAfterSeconds        a detour is tried after waiting this long
     */
    public record Tunables(int lookaheadNodes, double lookaheadDistance, double occupiedPenalty,
            long blockedEventAfterSeconds, long replanAfterSeconds) {
    }

    public Tunables get() {
        TrafficSettings s = current();
        return new Tunables(s.lookaheadNodes(), s.lookaheadDistance(), s.occupiedPenalty(),
                s.blockedEventAfter().toSeconds(), s.replanAfter().toSeconds());
    }

    public Tunables update(Tunables t) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("lookaheadNodes", Integer.toString(t.lookaheadNodes()));
        values.put("lookaheadDistance", Double.toString(t.lookaheadDistance()));
        values.put("occupiedPenalty", Double.toString(t.occupiedPenalty()));
        values.put("blockedEventAfterSeconds", Long.toString(t.blockedEventAfterSeconds()));
        values.put("replanAfterSeconds", Long.toString(t.replanAfterSeconds()));
        store.save(values);
        apply(merge(current(), values));
        log.info("traffic parameters changed: {}", values);
        return get();
    }

    private TrafficSettings current() {
        return engine.call(c -> c.settings());
    }

    private void apply(TrafficSettings settings) {
        engine.call(c -> {
            c.updateSettings(settings);
            return null;
        });
    }

    private static TrafficSettings merge(TrafficSettings s, Map<String, String> v) {
        return new TrafficSettings(
                v.containsKey("lookaheadNodes") ? Integer.parseInt(v.get("lookaheadNodes")) : s.lookaheadNodes(),
                v.containsKey("lookaheadDistance") ? Double.parseDouble(v.get("lookaheadDistance")) : s.lookaheadDistance(),
                s.turnPenaltyPerRadian(), s.defaultSpeed(),
                v.containsKey("occupiedPenalty") ? Double.parseDouble(v.get("occupiedPenalty")) : s.occupiedPenalty(),
                s.nodeTolerance(),
                v.containsKey("blockedEventAfterSeconds")
                        ? Duration.ofSeconds(Long.parseLong(v.get("blockedEventAfterSeconds"))) : s.blockedEventAfter(),
                v.containsKey("replanAfterSeconds")
                        ? Duration.ofSeconds(Long.parseLong(v.get("replanAfterSeconds"))) : s.replanAfter());
    }
}
