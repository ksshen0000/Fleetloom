package org.fleetloom.resource.vehicle.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.fleetloom.resource.vehicle.domain.Vehicle;
import org.fleetloom.resource.vehicle.domain.VehicleRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisVehicleRepository implements VehicleRepository {

    private final VehicleMapper mapper;

    public MybatisVehicleRepository(VehicleMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Vehicle> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<VehicleEntity>().eq(VehicleEntity::getCode, code)))
                .map(MybatisVehicleRepository::toDomain);
    }

    @Override
    public Optional<Vehicle> findByIdentity(String manufacturer, String serialNumber) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<VehicleEntity>()
                        .eq(VehicleEntity::getManufacturer, manufacturer)
                        .eq(VehicleEntity::getSerialNumber, serialNumber)))
                .map(MybatisVehicleRepository::toDomain);
    }

    @Override
    public List<Vehicle> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<VehicleEntity>().orderByAsc(VehicleEntity::getCode)).stream()
                .map(MybatisVehicleRepository::toDomain).toList();
    }

    @Override
    public boolean existsOfType(String typeCode) {
        return mapper.exists(new LambdaQueryWrapper<VehicleEntity>().eq(VehicleEntity::getTypeCode, typeCode));
    }

    @Override
    public Vehicle save(Vehicle v) {
        VehicleEntity e = new VehicleEntity();
        e.setId(v.id());
        e.setCode(v.code());
        e.setName(v.name());
        e.setTypeCode(v.typeCode());
        e.setManufacturer(v.manufacturer());
        e.setSerialNumber(v.serialNumber());
        e.setSimulated(v.simulated());
        e.setEnabled(v.enabled());
        e.setInitialPoint(v.initialPoint());
        e.setChargeThresholdLow(v.chargeThresholdLow());
        e.setChargeThresholdHigh(v.chargeThresholdHigh());
        e.setCreatedAt(v.createdAt());
        e.setUpdatedAt(v.updatedAt());
        if (v.id() == null) {
            mapper.insert(e);
        } else {
            e.setInitialPoint(null); // written by the explicit set(...) below, which also clears it
            mapper.update(e, new LambdaUpdateWrapper<VehicleEntity>()
                    .eq(VehicleEntity::getId, v.id())
                    .set(VehicleEntity::getInitialPoint, v.initialPoint()));
        }
        return v.id() == null ? toDomain(e) : v;
    }

    @Override
    public void delete(Vehicle vehicle) {
        mapper.deleteById(vehicle.id());
    }

    private static Vehicle toDomain(VehicleEntity e) {
        return new Vehicle(e.getId(), e.getCode(), e.getName(), e.getTypeCode(), e.getManufacturer(), e.getSerialNumber(),
                e.getSimulated(), e.getEnabled(), e.getInitialPoint(), e.getChargeThresholdLow(), e.getChargeThresholdHigh(),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
