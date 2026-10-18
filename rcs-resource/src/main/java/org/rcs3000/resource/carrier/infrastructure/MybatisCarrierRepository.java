package org.rcs3000.resource.carrier.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.carrier.domain.Carrier;
import org.rcs3000.resource.carrier.domain.CarrierErrorCode;
import org.rcs3000.resource.carrier.domain.CarrierLocationType;
import org.rcs3000.resource.carrier.domain.CarrierMovement;
import org.rcs3000.resource.carrier.domain.CarrierRepository;
import org.rcs3000.resource.carrier.domain.CarrierStatus;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisCarrierRepository implements CarrierRepository {

    private final CarrierMapper mapper;
    private final CarrierMovementMapper movements;

    public MybatisCarrierRepository(CarrierMapper mapper, CarrierMovementMapper movements) {
        this.mapper = mapper;
        this.movements = movements;
    }

    @Override
    public Optional<Carrier> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<CarrierEntity>().eq(CarrierEntity::getCode, code)))
                .map(MybatisCarrierRepository::toDomain);
    }

    @Override
    public List<Carrier> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<CarrierEntity>().orderByAsc(CarrierEntity::getCode)).stream()
                .map(MybatisCarrierRepository::toDomain).toList();
    }

    @Override
    public boolean existsOfType(String typeCode) {
        return mapper.exists(new LambdaQueryWrapper<CarrierEntity>().eq(CarrierEntity::getTypeCode, typeCode));
    }

    @Override
    public void insert(Carrier carrier) {
        CarrierEntity e = toEntity(carrier);
        mapper.insert(e);
        carrier.assignId(e.getId());
    }

    @Override
    public void update(Carrier carrier) {
        CarrierEntity e = toEntity(carrier);
        e.setVersion(carrier.getVersion() + 1);
        e.setLocationRef(null); // written by the explicit set(...) below, which also clears it
        int updated = mapper.update(e, new LambdaUpdateWrapper<CarrierEntity>()
                .eq(CarrierEntity::getId, carrier.getId())
                .eq(CarrierEntity::getVersion, carrier.getVersion())
                .set(CarrierEntity::getLocationRef, carrier.getLocationRef()));
        if (updated != 1) {
            throw new BizException(CarrierErrorCode.CARRIER_CONCURRENT_MODIFICATION,
                    "carrier %s was changed concurrently, retry".formatted(carrier.getCode()));
        }
        carrier.versionStored(carrier.getVersion() + 1);
    }

    @Override
    public void delete(Carrier carrier) {
        mapper.deleteById(carrier.getId());
    }

    @Override
    public void record(CarrierMovement m) {
        CarrierMovementEntity e = new CarrierMovementEntity();
        e.setCarrierCode(m.carrierCode());
        e.setFromType(m.fromType().name());
        e.setFromRef(m.fromRef());
        e.setToType(m.toType().name());
        e.setToRef(m.toRef());
        e.setOrderRef(m.orderRef());
        e.setMovedAt(m.movedAt());
        movements.insert(e);
    }

    @Override
    public List<CarrierMovement> movements(String carrierCode, int limit) {
        return movements.selectList(new LambdaQueryWrapper<CarrierMovementEntity>()
                        .eq(CarrierMovementEntity::getCarrierCode, carrierCode)
                        .orderByDesc(CarrierMovementEntity::getMovedAt)
                        .orderByDesc(CarrierMovementEntity::getId)
                        .last("LIMIT " + Math.max(1, limit)))
                .stream()
                .map(e -> new CarrierMovement(e.getCarrierCode(), CarrierLocationType.valueOf(e.getFromType()), e.getFromRef(),
                        CarrierLocationType.valueOf(e.getToType()), e.getToRef(), e.getOrderRef(), e.getMovedAt()))
                .toList();
    }

    private static Carrier toDomain(CarrierEntity e) {
        return new Carrier(e.getId(), e.getCode(), e.getTypeCode(), CarrierStatus.valueOf(e.getStatus()),
                CarrierLocationType.valueOf(e.getLocationType()), e.getLocationRef(), e.getLastMovedAt(), e.getVersion(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private static CarrierEntity toEntity(Carrier c) {
        CarrierEntity e = new CarrierEntity();
        e.setId(c.getId());
        e.setCode(c.getCode());
        e.setTypeCode(c.getTypeCode());
        e.setStatus(c.getStatus().name());
        e.setLocationType(c.getLocationType().name());
        e.setLocationRef(c.getLocationRef());
        e.setLastMovedAt(c.getLastMovedAt());
        e.setVersion(c.getVersion());
        e.setCreatedAt(c.getCreatedAt());
        e.setUpdatedAt(c.getUpdatedAt());
        return e;
    }
}
