package org.rcs3000.vda5050.v2.factsheet;

import java.util.List;

import org.rcs3000.vda5050.v2.AgvClass;
import org.rcs3000.vda5050.v2.AgvKinematic;
import org.rcs3000.vda5050.v2.LocalizationType;
import org.rcs3000.vda5050.v2.NavigationType;

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
