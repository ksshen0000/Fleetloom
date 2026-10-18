package org.rcs3000.resource.carrier.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.rcs3000.resource.CodeSets;
import org.rcs3000.resource.carrier.domain.CarrierCategory;
import org.rcs3000.resource.carrier.domain.CarrierType;
import org.rcs3000.resource.carrier.domain.CarrierTypeRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisCarrierTypeRepository implements CarrierTypeRepository {

    private final CarrierTypeMapper mapper;

    public MybatisCarrierTypeRepository(CarrierTypeMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<CarrierType> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<CarrierTypeEntity>()
                .eq(CarrierTypeEntity::getCode, code))).map(MybatisCarrierTypeRepository::toDomain);
    }

    @Override
    public List<CarrierType> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<CarrierTypeEntity>().orderByAsc(CarrierTypeEntity::getCode))
                .stream().map(MybatisCarrierTypeRepository::toDomain).toList();
    }

    @Override
    public CarrierType save(CarrierType t) {
        CarrierTypeEntity e = new CarrierTypeEntity();
        e.setId(t.id());
        e.setCode(t.code());
        e.setName(t.name());
        e.setCategory(t.category().name());
        e.setLength(t.length());
        e.setWidth(t.width());
        e.setHeight(t.height());
        e.setMaxLoad(t.maxLoad());
        e.setCompatibleVehicleTypes(CodeSets.join(t.compatibleVehicleTypes()));
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
    public void delete(CarrierType type) {
        mapper.deleteById(type.id());
    }

    private static CarrierType toDomain(CarrierTypeEntity e) {
        return new CarrierType(e.getId(), e.getCode(), e.getName(), CarrierCategory.valueOf(e.getCategory()),
                e.getLength(), e.getWidth(), e.getHeight(), e.getMaxLoad(), CodeSets.split(e.getCompatibleVehicleTypes()),
                e.getCreatedAt(), e.getUpdatedAt());
    }
}
