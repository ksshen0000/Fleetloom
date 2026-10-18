package org.rcs3000.resource.map.infrastructure.persistence;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.rcs3000.resource.map.domain.MapRepository;
import org.rcs3000.resource.map.domain.RcsMap;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisMapRepository implements MapRepository {

    private final MapMapper mapper;

    public MybatisMapRepository(MapMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<RcsMap> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(byCode(code))).map(MybatisMapRepository::toDomain);
    }

    @Override
    public boolean existsByCode(String code) {
        return mapper.exists(byCode(code));
    }

    @Override
    public List<RcsMap> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<MapEntity>().orderByAsc(MapEntity::getCode)).stream()
                .map(MybatisMapRepository::toDomain)
                .toList();
    }

    @Override
    public void save(RcsMap map) {
        MapEntity entity = toEntity(map);
        if (map.getId() == null) {
            mapper.insert(entity);
            map.assignId(entity.getId());
        } else {
            mapper.updateById(entity);
        }
    }

    @Override
    public void delete(RcsMap map) {
        mapper.deleteById(map.getId());
    }

    private static LambdaQueryWrapper<MapEntity> byCode(String code) {
        return new LambdaQueryWrapper<MapEntity>().eq(MapEntity::getCode, code);
    }

    private static RcsMap toDomain(MapEntity e) {
        return new RcsMap(e.getId(), e.getCode(), e.getName(), e.getDescription(), e.getActiveVersionNo(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    private static MapEntity toEntity(RcsMap map) {
        MapEntity e = new MapEntity();
        e.setId(map.getId());
        e.setCode(map.getCode());
        e.setName(map.getName());
        e.setDescription(map.getDescription());
        e.setActiveVersionNo(map.getActiveVersionNo());
        e.setCreatedAt(map.getCreatedAt());
        e.setUpdatedAt(map.getUpdatedAt());
        return e;
    }
}
