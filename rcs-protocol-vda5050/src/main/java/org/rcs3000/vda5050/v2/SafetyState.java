package org.rcs3000.vda5050.v2;

public record SafetyState(EStop eStop, boolean fieldViolation) {

    public static final SafetyState CLEAR = new SafetyState(EStop.NONE, false);
}
