package org.fleetloom.traffic.core;

import org.fleetloom.contracts.map.MapTopologyDto;

/** Outbound port to the map service. */
public interface MapProvider {

    /** @throws RuntimeException if the map has no published version or the service is unreachable */
    MapTopologyDto activeTopology(String mapCode);
}
