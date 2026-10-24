package org.fleetloom.vda5050.v2.factsheet;

import java.util.List;

import org.fleetloom.vda5050.v2.AgvClass;
import org.fleetloom.vda5050.v2.AgvKinematic;
import org.fleetloom.vda5050.v2.LocalizationType;
import org.fleetloom.vda5050.v2.NavigationType;

/** @param maxLoadMass kg */
public record TypeSpecification(
        String seriesName,
        String seriesDescription,
        AgvKinematic agvKinematic,
        AgvClass agvClass,
        double maxLoadMass,
        List<LocalizationType> localizationTypes,
        List<NavigationType> navigationTypes) {
}
