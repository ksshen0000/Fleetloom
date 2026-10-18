package org.rcs3000.resource.storage.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.rcs3000.resource.storage.domain.Zone;
import org.rcs3000.resource.storage.domain.ZoneRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisZoneRepository implements ZoneRepository {

    private final ZoneMapper mapper;

    public MybatisZoneRepository(ZoneMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Zone> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<ZoneEntity>().eq(ZoneEntity::getCode, code)))
                .map(MybatisZoneRepository::toDomain);
    }

    @Override
    public List<Zone> findAll() {
        return mapper.selectList(new LambdaQueryWrapper<ZoneEntity>().orderByAsc(ZoneEntity::getCode)).stream()
                .map(MybatisZoneRepository::toDomain).toList();
    }

    @Override
    public Zone save(Zone zone) {
        ZoneEntity e = new ZoneEntity();
        e.setId(zone.id());
        e.setCode(zone.code());
        e.setName(zone.name());
        e.setType(zone.type());
        e.setDescription(zone.description());
        e.setCreatedAt(zone.createdAt());
        e.setUpdatedAt(zone.updatedAt());
        if (zone.id() == null) {
            mapper.insert(e);
        } else {
            mapper.update(e, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<ZoneEntity>()
                    .eq(ZoneEntity::getId, zone.id())
                    .set(ZoneEntity::getDescription, zone.description()));
        }
        return toDomain(e);
    }

    @Override
    public void delete(Zone zone) {
        mapper.deleteById(zone.id());
    }

    private static Zone toDomain(ZoneEntity e) {
        return new Zone(e.getId(), e.getCode(), e.getName(), e.getType(), e.getDescription(), e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
