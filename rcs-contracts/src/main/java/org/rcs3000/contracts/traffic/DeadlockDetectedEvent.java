package org.rcs3000.contracts.traffic;

import java.time.Instant;
import java.util.List;

/** The listed vehicles wait for each other in a cycle. */
public record DeadlockDetectedEvent(List<String> vehicleCodes, Instant at) {

    public static final String TYPE = "DeadlockDetected";
}
