package org.fleetloom.vda5050.v2.factsheet;

/** Intervals in seconds. */
public record Timing(double minOrderInterval, double minStateInterval, Double defaultStateInterval,
        Double visualizationInterval) {
}
