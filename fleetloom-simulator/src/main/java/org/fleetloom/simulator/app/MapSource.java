package org.fleetloom.simulator.app;

import org.fleetloom.simulator.core.SiteMap;

/** Where the simulated vehicles learn the site layout from: a file, or a fleet manager's map service. */
public interface MapSource {

    /** @throws RuntimeException when the map cannot be read */
    SiteMap load();

    /** For log messages, e.g. the file name. */
    String describe();
}
