package org.rcs3000.traffic.app;

import java.util.Map;

/** Persists the traffic parameters changed at runtime, by name. */
public interface SettingStore {

    Map<String, String> load();

    void save(Map<String, String> values);
}
