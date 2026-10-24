package org.fleetloom.vda5050.v2.factsheet;

import org.fleetloom.vda5050.v2.ValueDataType;

public record ActionParameterSpec(String key, ValueDataType valueDataType, String description, Boolean isOptional) {
}
