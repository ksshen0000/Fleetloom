package org.fleetloom.simulator.platform;

import org.fleetloom.simulator.app.MapSource;
import org.fleetloom.simulator.core.SiteMap;

/** The active version of a map of the Fleetloom map service. */
final class PlatformMapSource implements MapSource {

    private final MapClient client;
    private final String mapCode;
    private final String resourceUrl;

    PlatformMapSource(MapClient client, String mapCode, String resourceUrl) {
        this.client = client;
        this.mapCode = mapCode;
        this.resourceUrl = resourceUrl;
    }

    @Override
    public SiteMap load() {
        return SiteMaps.from(client.activeTopology(mapCode));
    }

    @Override
    public String describe() {
        return "map " + mapCode + " of " + resourceUrl;
    }
}
