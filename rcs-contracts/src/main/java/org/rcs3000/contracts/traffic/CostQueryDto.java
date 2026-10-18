package org.rcs3000.contracts.traffic;

import java.util.List;

/** Travel cost from each {@code from} point to each {@code to} point (requirement TRF-05). */
public record CostQueryDto(String mapCode, List<String> from, List<String> to) {
}
