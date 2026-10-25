package org.fleetloom.contracts.traffic;

import java.util.List;

/**
 * Travel cost from each {@code from} point to each {@code to} point (requirement TRF-05).
 *
 * @param vehicles optional, one per {@code from} point: the vehicle standing there, so that its row is
 *                 for that vehicle's speed and the paths open to its type
 */
public record CostQueryDto(String mapCode, List<String> from, List<String> to, List<String> vehicles) {

    public CostQueryDto(String mapCode, List<String> from, List<String> to) {
        this(mapCode, from, to, null);
    }
}
