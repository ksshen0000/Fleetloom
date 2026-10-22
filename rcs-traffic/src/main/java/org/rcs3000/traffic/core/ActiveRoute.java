package org.rcs3000.traffic.core;

import java.time.Instant;
import java.util.List;

import org.rcs3000.contracts.fleet.VehicleActionDto;
import org.rcs3000.contracts.traffic.RouteStatus;

/** Mutable progress of one route; owned by the controller thread. */
final class ActiveRoute {

    final String routeId;
    final String mapCode;
    final List<VehicleActionDto> targetActions;
    final Instant createdAt;
    PlannedRoute plan;
    String orderId;
    /** the vehicle has reported {@link #orderId} as its order at least once */
    boolean acknowledged;
    int passedIndex;
    int releasedIndex;
    RouteStatus status;
    String blockedBy;
    Resource blockedResource;
    Instant waitingSince;
    boolean blockedEventSent;

    ActiveRoute(String routeId, String mapCode, PlannedRoute plan, List<VehicleActionDto> targetActions, Instant createdAt) {
        this.routeId = routeId;
        this.mapCode = mapCode;
        this.plan = plan;
        this.targetActions = targetActions == null ? List.of() : List.copyOf(targetActions);
        this.createdAt = createdAt;
        this.status = RouteStatus.WAITING;
    }

    String target() {
        return plan.target();
    }

    /** The vehicle stands at the end of what it may drive and cannot go further yet. */
    boolean isStuck() {
        return passedIndex == releasedIndex && releasedIndex < plan.lastIndex() && blockedBy != null;
    }

    void clearBlocked() {
        blockedBy = null;
        blockedResource = null;
        waitingSince = null;
        blockedEventSent = false;
    }
}
