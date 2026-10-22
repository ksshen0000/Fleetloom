package org.rcs3000.dispatch.core;

import java.util.List;

/**
 * How a transport order is broken into legs (DSP-03): drive to the source and do {@code pickAction},
 * stop at each via point (doing {@code viaAction} there, if any), drive to the target and do
 * {@code dropAction}. E.g. a jacking AGV uses {@code lift} / {@code lower} instead of {@code pick} /
 * {@code drop}; a route past a weighing station adds it as a via point.
 *
 * @param pickAction VDA 5050 action type at the source, {@code null} to just stop there
 * @param dropAction VDA 5050 action type at the target, {@code null} to just stop there
 * @param viaPoints  map points visited in this order between pick-up and drop-off
 */
public record OrderTemplate(String code, String name, String pickAction, String dropAction, List<String> viaPoints,
        String viaAction, String description) {

    /** The template used when an order names none; it cannot be deleted. */
    public static final String STANDARD = "STANDARD";

    public OrderTemplate {
        pickAction = blankToNull(pickAction);
        dropAction = blankToNull(dropAction);
        viaAction = blankToNull(viaAction);
        viaPoints = viaPoints == null ? List.of() : viaPoints.stream().filter(p -> p != null && !p.isBlank()).toList();
    }

    public static OrderTemplate standard() {
        return new OrderTemplate(STANDARD, "标准取放", "pick", "drop", List.of(), null, null);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
