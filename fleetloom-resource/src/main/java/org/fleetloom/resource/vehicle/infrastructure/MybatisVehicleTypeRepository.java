package org.fleetloom.resource.vehicle.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.fleetloom.resource.CodeSets;
import org.fleetloom.resource.vehicle.domain.VehicleCategory;
import org.fleetloom.resource.vehicle.domain.VehicleType;
import org.fleetloom.resource.vehicle.domain.VehicleTypeRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisVehicleTypeRepository implements VehicleTypeRepository {

    private final VehicleTypeMapper mapper;

    public MybatisVehicleTypeRepository(VehicleTypeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<VehicleType> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<VehicleTypeEntity>()
                .eq(VehicleTypeEntity::getCode, code))).map(MybatisVehicleTypeRepository::toDomain);
    }

    @Override
    public List<VehicleType> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<VehicleTypeEntity>().orderByAsc(VehicleTypeEntity::getCode))
                .stream().map(MybatisVehicleTypeRepository::toDomain).toList();
    }

    @Override
    public VehicleType save(VehicleType t) {
        VehicleTypeEntity e = new VehicleTypeEntity();
        e.setId(t.id());
        e.setCode(t.code());
        e.setName(t.name());
        e.setCategory(t.category().name());
        e.setLength(t.length());
        e.setWidth(t.width());
        e.setMaxSpeed(t.maxSpeed());
        e.setMaxLoad(t.maxLoad());
        e.setSupportedActions(CodeSets.join(t.supportedActions()));
        e.setCreatedAt(t.createdAt());
        e.setUpdatedAt(t.updatedAt());
        if (t.id() == null) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }
        return toDomain(e);
    }

    @Override
    public void delete(VehicleType type) {
        mapper.deleteById(type.id());
    }

    private static VehicleType toDomain(VehicleTypeEntity e) {
        return new VehicleType(e.getId(), e.getCode(), e.getName(), VehicleCategory.valueOf(e.getCategory()), e.getLength(),
                e.getWidth(), e.getMaxSpeed(), e.getMaxLoad(), CodeSets.split(e.getSupportedActions()), e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
