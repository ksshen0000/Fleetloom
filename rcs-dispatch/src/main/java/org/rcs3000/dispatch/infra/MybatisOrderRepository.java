package org.rcs3000.dispatch.infra;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.rcs3000.common.error.BizException;
import org.rcs3000.contracts.dispatch.OrderStatus;
import org.rcs3000.contracts.dispatch.OrderType;
import org.rcs3000.dispatch.core.DispatchErrorCode;
import org.rcs3000.dispatch.core.Leg;
import org.rcs3000.dispatch.core.Ports;
import org.rcs3000.dispatch.core.TransportOrder;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

public class MybatisOrderRepository implements Ports.OrderRepository {

    private static final List<String> FINAL = List.of(OrderStatus.COMPLETED.name(), OrderStatus.FAILED.name(),
            OrderStatus.CANCELLED.name(), OrderStatus.REJECTED.name());

    private final TransportOrderMapper mapper;
    private final JsonMapper json;

    public MybatisOrderRepository(TransportOrderMapper mapper, JsonMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override
    public void insert(TransportOrder order) {
        TransportOrderEntity e = toEntity(order);
        mapper.insert(e);
        order.assignId(e.getId());
    }

    @Override
    public void update(TransportOrder order) {
        TransportOrderEntity e = toEntity(order);
        e.setVersion(order.version() + 1);
        // nullable columns are written by the explicit set(...) below only, so they can also be cleared
        e.setAssignedVehicle(null);
        e.setReason(null);
        e.setRouteId(null);
        e.setFinishedAt(null);
        int updated = mapper.update(e, new LambdaUpdateWrapper<TransportOrderEntity>()
                .eq(TransportOrderEntity::getId, order.id())
                .eq(TransportOrderEntity::getVersion, order.version())
                .set(TransportOrderEntity::getAssignedVehicle, order.assignedVehicle())
                .set(TransportOrderEntity::getReason, truncate(order.reason()))
                .set(TransportOrderEntity::getRouteId, order.routeId())
                .set(TransportOrderEntity::getFinishedAt, order.finishedAt()));
        if (updated != 1) {
            throw new BizException(DispatchErrorCode.ORDER_CONCURRENT_MODIFICATION,
                    "order %s was changed concurrently".formatted(order.orderNo()));
        }
        order.versionStored(order.version() + 1);
    }

    @Override
    public Optional<TransportOrder> findByNo(String orderNo) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<TransportOrderEntity>()
                .eq(TransportOrderEntity::getOrderNo, orderNo))).map(this::toDomain);
    }

    @Override
    public Optional<TransportOrder> findByExternalNo(String externalNo) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<TransportOrderEntity>()
                .eq(TransportOrderEntity::getExternalNo, externalNo))).map(this::toDomain);
    }

    @Override
    public List<TransportOrder> findActive() {
        return mapper.selectList(new LambdaQueryWrapper<TransportOrderEntity>()
                        .notIn(TransportOrderEntity::getStatus, FINAL)
                        .orderByAsc(TransportOrderEntity::getCreatedAt))
                .stream().map(this::toDomain).toList();
    }

    @Override
    public List<TransportOrder> find(OrderStatus status, int limit) {
        return mapper.selectList(new LambdaQueryWrapper<TransportOrderEntity>()
                        .eq(status != null, TransportOrderEntity::getStatus, status == null ? null : status.name())
                        .orderByDesc(TransportOrderEntity::getCreatedAt)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream().map(this::toDomain).toList();
    }

    private TransportOrder toDomain(TransportOrderEntity e) {
        List<Leg> legs = json.readValue(e.getLegs(), new TypeReference<List<Leg>>() { });
        return new TransportOrder(e.getId(), e.getOrderNo(), e.getExternalNo(), OrderType.valueOf(e.getType()),
                e.getPriority(), e.getCarrierCode(), e.getCarrierType(), e.getSourceLocation(), e.getTargetLocation(),
                e.getMapCode(), e.getRequestedVehicle(), e.getAssignedVehicle(), OrderStatus.valueOf(e.getStatus()),
                e.getCurrentLeg(), legs, e.getReason(), e.getRouteId(), e.getCreatedAt(), e.getUpdatedAt(),
                e.getFinishedAt(), e.getVersion()).withDeadline(e.getDeadline());
    }

    private TransportOrderEntity toEntity(TransportOrder o) {
        TransportOrderEntity e = new TransportOrderEntity();
        e.setId(o.id());
        e.setOrderNo(o.orderNo());
        e.setExternalNo(o.externalNo());
        e.setType(o.type().name());
        e.setPriority(o.priority());
        e.setCarrierCode(o.carrierCode());
        e.setCarrierType(o.carrierType());
        e.setSourceLocation(o.sourceLocation());
        e.setTargetLocation(o.targetLocation());
        e.setMapCode(o.mapCode());
        e.setRequestedVehicle(o.requestedVehicle());
        e.setAssignedVehicle(o.assignedVehicle());
        e.setStatus(o.status().name());
        e.setCurrentLeg(o.currentLeg());
        e.setLegs(json.writeValueAsString(o.legs()));
        e.setReason(truncate(o.reason()));
        e.setRouteId(o.routeId());
        e.setCreatedAt(o.createdAt());
        e.setUpdatedAt(o.updatedAt());
        e.setFinishedAt(o.finishedAt());
        e.setVersion(o.version());
        e.setDeadline(o.deadline());
        return e;
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 512 ? s : s.substring(0, 509) + "...";
    }
}
