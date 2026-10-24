package org.fleetloom.dispatch.core;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderType;
import org.fleetloom.contracts.dispatch.OrderViewDto;

/** A transport order and its progress. Mutable; owned by the dispatcher's thread. */
public final class TransportOrder {

    Long id;
    final String orderNo;
    final String externalNo;
    final OrderType type;
    final int priority;
    final String carrierCode;
    String carrierType;
    String sourceLocation;
    String targetLocation;
    String mapCode;
    /** Vehicle the requester insisted on, or {@code null}. */
    final String requestedVehicle;
    String assignedVehicle;
    OrderStatus status;
    int currentLeg;
    final List<Leg> legs = new ArrayList<>();
    String reason;
    /** Traffic route currently driven for this order. */
    String routeId;
    final Instant createdAt;
    Instant updatedAt;
    Instant finishedAt;
    int version;
    /** When the order should be finished, or {@code null}; set once at creation. */
    Instant deadline;
    /** Whether {@code OrderOverdueEvent} was sent; not stored, a restart may send it once more. */
    boolean overdueReported;
    /** Since when sending the vehicle on its current leg fails for a passing reason; not stored. */
    Instant failingSince;

    public TransportOrder(Long id, String orderNo, String externalNo, OrderType type, int priority, String carrierCode,
            String carrierType, String sourceLocation, String targetLocation, String mapCode, String requestedVehicle, String assignedVehicle,
            OrderStatus status, int currentLeg, List<Leg> legs, String reason, String routeId, Instant createdAt,
            Instant updatedAt, Instant finishedAt, int version) {
        this.id = id;
        this.orderNo = orderNo;
        this.externalNo = externalNo;
        this.type = type;
        this.priority = priority;
        this.carrierCode = carrierCode;
        this.carrierType = carrierType;
        this.sourceLocation = sourceLocation;
        this.targetLocation = targetLocation;
        this.mapCode = mapCode;
        this.requestedVehicle = requestedVehicle;
        this.assignedVehicle = assignedVehicle;
        this.status = status;
        this.currentLeg = currentLeg;
        this.legs.addAll(legs);
        this.reason = reason;
        this.routeId = routeId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.finishedAt = finishedAt;
        this.version = version;
    }

    Leg leg() {
        return legs.get(currentLeg);
    }

    void markLeg(Leg.Status status) {
        legs.set(currentLeg, leg().with(status));
    }

    boolean carrierOnVehicle() {
        return type == OrderType.TRANSPORT && legs.getFirst().status() == Leg.Status.DONE
                && legs.getLast().status() != Leg.Status.DONE;
    }

    public OrderViewDto view() {
        return new OrderViewDto(orderNo, externalNo, type, priority, status, carrierCode, sourceLocation, targetLocation,
                mapCode, assignedVehicle, currentLeg,
                legs.stream().map(l -> new OrderViewDto.Leg(l.point(), l.action(), l.status().name())).toList(),
                reason, createdAt, updatedAt, finishedAt, deadline);
    }

    // accessors for persistence adapters
    public Long id() { return id; }
    public String orderNo() { return orderNo; }
    public String externalNo() { return externalNo; }
    public OrderType type() { return type; }
    public int priority() { return priority; }
    public String carrierCode() { return carrierCode; }
    public String carrierType() { return carrierType; }
    public String sourceLocation() { return sourceLocation; }
    public String targetLocation() { return targetLocation; }
    public String mapCode() { return mapCode; }
    public String requestedVehicle() { return requestedVehicle; }
    public String assignedVehicle() { return assignedVehicle; }
    public OrderStatus status() { return status; }
    public int currentLeg() { return currentLeg; }
    public List<Leg> legs() { return List.copyOf(legs); }
    public String reason() { return reason; }
    public String routeId() { return routeId; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant finishedAt() { return finishedAt; }
    public int version() { return version; }
    public Instant deadline() { return deadline; }

    public TransportOrder withDeadline(Instant deadline) {
        this.deadline = deadline;
        return this;
    }

    public void assignId(Long id) {
        this.id = id;
    }

    public void versionStored(int newVersion) {
        this.version = newVersion;
    }
}
