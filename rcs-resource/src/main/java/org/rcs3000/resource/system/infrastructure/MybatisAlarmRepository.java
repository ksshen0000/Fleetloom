package org.rcs3000.resource.system.infrastructure;

import java.util.List;
import java.util.Optional;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.rcs3000.resource.system.domain.Alarm;
import org.rcs3000.resource.system.domain.AlarmCategory;
import org.rcs3000.resource.system.domain.AlarmLevel;
import org.rcs3000.resource.system.domain.AlarmRepository;
import org.rcs3000.resource.system.domain.AlarmStatus;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisAlarmRepository implements AlarmRepository {

    private static final String CLEARED = AlarmStatus.CLEARED.name();

    private final AlarmMapper mapper;

    public MybatisAlarmRepository(AlarmMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Optional<Alarm> findById(long id) {
        return Optional.ofNullable(mapper.selectById(id)).map(MybatisAlarmRepository::toDomain);
    }

    @Override
    public List<Alarm> findOpen() {
        return mapper.selectList(new LambdaQueryWrapper<AlarmEntity>().ne(AlarmEntity::getStatus, CLEARED)).stream()
                .map(MybatisAlarmRepository::toDomain).toList();
    }

    @Override
    public List<Alarm> find(Boolean open, AlarmLevel level, String vehicleCode, int limit) {
        LambdaQueryWrapper<AlarmEntity> q = new LambdaQueryWrapper<AlarmEntity>()
                .ne(Boolean.TRUE.equals(open), AlarmEntity::getStatus, CLEARED)
                .eq(Boolean.FALSE.equals(open), AlarmEntity::getStatus, CLEARED)
                .eq(level != null, AlarmEntity::getLevel, level == null ? null : level.name())
                .eq(vehicleCode != null, AlarmEntity::getVehicleCode, vehicleCode)
                .orderByDesc(AlarmEntity::getRaisedAt)
                .last("LIMIT " + limit);
        return mapper.selectList(q).stream().map(MybatisAlarmRepository::toDomain).toList();
    }

    @Override
    public Alarm save(Alarm a) {
        AlarmEntity e = new AlarmEntity();
        e.setId(a.id());
        e.setAlarmKey(a.alarmKey());
        e.setCategory(a.category().name());
        e.setLevel(a.level().name());
        e.setVehicleCode(a.vehicleCode());
        e.setOrderNo(a.orderNo());
        e.setMessage(a.message());
        e.setStatus(a.status().name());
        e.setOccurrences(a.occurrences());
        e.setRaisedAt(a.raisedAt());
        e.setLastSeenAt(a.lastSeenAt());
        e.setAcknowledgedBy(a.acknowledgedBy());
        e.setAcknowledgedAt(a.acknowledgedAt());
        e.setClearedBy(a.clearedBy());
        e.setClearedAt(a.clearedAt());
        if (a.id() == null) {
            mapper.insert(e);
        } else {
            mapper.updateById(e);
        }
        return toDomain(e);
    }

    private static Alarm toDomain(AlarmEntity e) {
        return new Alarm(e.getId(), e.getAlarmKey(), AlarmCategory.valueOf(e.getCategory()), AlarmLevel.valueOf(e.getLevel()),
                e.getVehicleCode(), e.getOrderNo(), e.getMessage(), AlarmStatus.valueOf(e.getStatus()), e.getOccurrences(),
                e.getRaisedAt(), e.getLastSeenAt(), e.getAcknowledgedBy(), e.getAcknowledgedAt(), e.getClearedBy(),
                e.getClearedAt());
    }
}
