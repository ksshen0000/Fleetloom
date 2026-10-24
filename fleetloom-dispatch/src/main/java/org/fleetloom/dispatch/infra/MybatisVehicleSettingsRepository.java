package org.fleetloom.dispatch.infra;

import java.time.Clock;
import java.util.Set;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.fleetloom.dispatch.core.Ports;

public class MybatisVehicleSettingsRepository implements Ports.VehicleSettingsRepository {

    private final VehicleSettingMapper mapper;
    private final Clock clock;

    public MybatisVehicleSettingsRepository(VehicleSettingMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public Set<String> notAccepting() {
        return mapper.selectList(new LambdaQueryWrapper<VehicleSettingEntity>().eq(VehicleSettingEntity::getAccepting, false))
                .stream().map(VehicleSettingEntity::getVehicleCode).collect(Collectors.toSet());
    }

    @Override
    public void setAccepting(String vehicleCode, boolean accepting) {
        VehicleSettingEntity e = new VehicleSettingEntity();
        e.setVehicleCode(vehicleCode);
        e.setAccepting(accepting);
        e.setUpdatedAt(clock.instant());
        if (mapper.updateById(e) == 0) {
            mapper.insert(e);
        }
    }
}
