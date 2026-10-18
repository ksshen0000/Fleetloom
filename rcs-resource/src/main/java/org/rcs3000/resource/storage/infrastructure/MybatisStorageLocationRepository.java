package org.rcs3000.resource.storage.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.rcs3000.common.error.BizException;
import org.rcs3000.resource.CodeSets;
import org.rcs3000.resource.storage.domain.LocationFilter;
import org.rcs3000.resource.storage.domain.StorageErrorCode;
import org.rcs3000.resource.storage.domain.StorageLocation;
import org.rcs3000.resource.storage.domain.StorageLocationRepository;
import org.rcs3000.resource.storage.domain.StorageLocationStatus;
import org.rcs3000.resource.storage.domain.StorageLocationType;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisStorageLocationRepository implements StorageLocationRepository {

    private final StorageLocationMapper mapper;

    public MybatisStorageLocationRepository(StorageLocationMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<StorageLocation> findByCode(String code) {
        return Optional.ofNullable(mapper.selectOne(new LambdaQueryWrapper<StorageLocationEntity>()
                .eq(StorageLocationEntity::getCode, code))).map(MybatisStorageLocationRepository::toDomain);
    }

    @Override
    public List<StorageLocation> find(LocationFilter filter) {
        return mapper.selectList(new LambdaQueryWrapper<StorageLocationEntity>()
                        .eq(filter.zoneCode() != null, StorageLocationEntity::getZoneCode, filter.zoneCode())
                        .eq(filter.status() != null, StorageLocationEntity::getStatus,
                                filter.status() == null ? null : filter.status().name())
                        .eq(filter.mapCode() != null, StorageLocationEntity::getMapCode, filter.mapCode())
                        .orderByAsc(StorageLocationEntity::getCode))
                .stream().map(MybatisStorageLocationRepository::toDomain).toList();
    }

    @Override
    public boolean existsInZone(String zoneCode) {
        return mapper.exists(new LambdaQueryWrapper<StorageLocationEntity>().eq(StorageLocationEntity::getZoneCode, zoneCode));
    }

    @Override
    public void insert(StorageLocation location) {
        StorageLocationEntity e = toEntity(location);
        mapper.insert(e);
        location.assignId(e.getId());
    }

    @Override
    public void update(StorageLocation location) {
        StorageLocationEntity e = toEntity(location);
        e.setVersion(location.getVersion() + 1);
        // nullable columns are written by the explicit set(...) below only; MyBatis-Plus would otherwise
        // also write the non-null entity fields and assign those columns twice
        e.setLockOwner(null);
        e.setCarrierCode(null);
        e.setStatusBeforeDisable(null);
        e.setAccessTheta(null);
        int updated = mapper.update(e, new LambdaUpdateWrapper<StorageLocationEntity>()
                .eq(StorageLocationEntity::getId, location.getId())
                .eq(StorageLocationEntity::getVersion, location.getVersion())
                // nullable columns are set explicitly: MyBatis-Plus skips null entity fields
                .set(StorageLocationEntity::getLockOwner, location.getLockOwner())
                .set(StorageLocationEntity::getCarrierCode, location.getCarrierCode())
                .set(StorageLocationEntity::getStatusBeforeDisable,
                        location.getStatusBeforeDisable() == null ? null : location.getStatusBeforeDisable().name())
                .set(StorageLocationEntity::getAccessTheta, location.getAccessTheta()));
        if (updated != 1) {
            throw new BizException(StorageErrorCode.STORAGE_CONCURRENT_MODIFICATION,
                    "location %s was changed concurrently, retry".formatted(location.getCode()));
        }
        location.versionStored(location.getVersion() + 1);
    }

    @Override
    public void delete(StorageLocation location) {
        mapper.deleteById(location.getId());
    }

    private static StorageLocation toDomain(StorageLocationEntity e) {
        return new StorageLocation(e.getId(), e.getCode(), e.getZoneCode(), StorageLocationType.valueOf(e.getType()),
                e.getMapCode(), e.getAccessPoint(), e.getAccessTheta(), e.getLevel(), e.getHeight(),
                CodeSets.split(e.getAllowedCarrierTypes()), StorageLocationStatus.valueOf(e.getStatus()),
                e.getStatusBeforeDisable() == null ? null : StorageLocationStatus.valueOf(e.getStatusBeforeDisable()),
                e.getLockOwner(), e.getCarrierCode(), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private static StorageLocationEntity toEntity(StorageLocation l) {
        StorageLocationEntity e = new StorageLocationEntity();
        e.setId(l.getId());
        e.setCode(l.getCode());
        e.setZoneCode(l.getZoneCode());
        e.setType(l.getType().name());
        e.setMapCode(l.getMapCode());
        e.setAccessPoint(l.getAccessPoint());
        e.setAccessTheta(l.getAccessTheta());
        e.setLevel(l.getLevel());
        e.setHeight(l.getHeight());
        e.setAllowedCarrierTypes(CodeSets.join(l.getAllowedCarrierTypes()));
        e.setStatus(l.getStatus().name());
        e.setStatusBeforeDisable(l.getStatusBeforeDisable() == null ? null : l.getStatusBeforeDisable().name());
        e.setLockOwner(l.getLockOwner());
        e.setCarrierCode(l.getCarrierCode());
        e.setVersion(l.getVersion());
        e.setCreatedAt(l.getCreatedAt());
        e.setUpdatedAt(l.getUpdatedAt());
        return e;
    }
}
