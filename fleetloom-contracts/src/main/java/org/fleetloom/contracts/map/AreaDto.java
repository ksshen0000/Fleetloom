package org.fleetloom.contracts.map;

import java.util.List;
import java.util.Map;

public record AreaDto(String code, AreaType type, List<PositionDto> polygon, Map<String, String> properties) {
}
