package org.rcs3000.contracts.traffic;

import java.util.List;

/** @param seconds {@code seconds.get(i).get(j)} = cost from {@code from[i]} to {@code to[j]}; {@code null} if unreachable */
public record CostMatrixDto(List<String> from, List<String> to, List<List<Double>> seconds) {
}
