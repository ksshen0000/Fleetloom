package org.rcs3000.vda5050.v2.factsheet;

import org.rcs3000.vda5050.v2.ValueDataType;

public record ActionParameterSpec(String key, ValueDataType valueDataType, String description, Boolean isOptional) {
}
