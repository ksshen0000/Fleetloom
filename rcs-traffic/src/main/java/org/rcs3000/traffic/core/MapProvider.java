package org.rcs3000.traffic.core;

import org.rcs3000.contracts.map.MapTopologyDto;

/** Outbound port to the map service. */
public interface MapProvider {

    /** @throws RuntimeException if the map has no published version or the service is unreachable */
    MapTopologyDto activeTopology(String mapCode);
}
