package org.fleetloom.resource.vehicle.infrastructure;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.fleetloom.resource.vehicle.domain.VehicleHistoryRepository;
import org.fleetloom.resource.vehicle.domain.VehicleSample;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisVehicleHistoryRepository implements VehicleHistoryRepository {

    private final VehicleHistoryMapper mapper;

    public MybatisVehicleHistoryRepository(VehicleHistoryMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void insert(VehicleSample s) {
        VehicleHistoryEntity e = new VehicleHistoryEntity();
        e.setVehicleCode(s.vehicleCode());
        e.setAt(s.at());
        e.setMapCode(s.mapCode());
        e.setX(s.x());
        e.setY(s.y());
        e.setTheta(s.theta());
        e.setBattery(s.battery());
        e.setCharging(s.charging());
        e.setDriving(s.driving());
        e.setLastNodeId(s.lastNodeId());
        e.setConnection(s.connection());
        mapper.insert(e);
    }

    @Override
    public List<VehicleSample> find(String vehicleCode, Instant from, Instant to, int limit) {
        LambdaQueryWrapper<VehicleHistoryEntity> q = new LambdaQueryWrapper<VehicleHistoryEntity>()
                .eq(VehicleHistoryEntity::getVehicleCode, vehicleCode)
                .ge(VehicleHistoryEntity::getAt, from)
                .lt(VehicleHistoryEntity::getAt, to)
                .orderByAsc(VehicleHistoryEntity::getAt)
                .last("LIMIT " + limit);
        return mapper.selectList(q).stream().map(e -> new VehicleSample(e.getVehicleCode(), e.getAt(), e.getMapCode(),
                e.getX(), e.getY(), e.getTheta(), e.getBattery(), e.getCharging(), e.getDriving(), e.getLastNodeId(),
                e.getConnection())).toList();
    }

    @Override
    public int deleteBefore(Instant cutoff) {
        return mapper.delete(new LambdaQueryWrapper<VehicleHistoryEntity>().lt(VehicleHistoryEntity::getAt, cutoff));
    }
}
