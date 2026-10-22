package org.rcs3000.dispatch.app;

import java.util.Map;

/** Persists the dispatch parameters changed at runtime, by name. */
public interface SettingStore {

    Map<String, String> load();

    void save(Map<String, String> values);
}
