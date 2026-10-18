package org.rcs3000.vda5050.v2.factsheet;

import java.util.List;

public record ProtocolFeatures(List<OptionalParameter> optionalParameters, List<AgvActionSpec> agvActions) {
}
