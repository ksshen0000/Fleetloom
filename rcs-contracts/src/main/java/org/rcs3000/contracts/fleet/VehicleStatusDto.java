package org.rcs3000.contracts.fleet;

import java.time.Instant;
import java.util.List;

/**
 * Live status of a vehicle as known to the fleet service.
 *
 * @param code           platform vehicle code (currently the VDA 5050 serial number)
 * @param lastSeen       when the last message of any kind arrived
 * @param remainingNodes nodes of the current order not yet traversed, base first
 */
public record VehicleStatusDto(
        String code,
        String manufacturer,
        String serialNumber,
        ConnectionStatus connection,
        Instant lastSeen,
        String mapCode,
        double x,
        double y,
        double theta,
        boolean positionInitialized,
        double velocity,
        boolean driving,
        boolean paused,
        String operatingMode,
        String orderId,
        long orderUpdateId,
        String lastNodeId,
        long lastNodeSequenceId,
        List<NodeRef> remainingNodes,
        double battery,
        boolean charging,
        List<String> loadIds,
        List<ActionStatus> actions,
        List<VehicleError> errors) {

    public record NodeRef(String nodeId, long sequenceId, boolean released) {
    }

    /** @param status VDA 5050 action status, e.g. RUNNING */
    public record ActionStatus(String actionId, String actionType, String status, String resultDescription) {
    }

    /** @param level WARNING or FATAL */
    public record VehicleError(String errorType, String level, String description) {
    }
}
