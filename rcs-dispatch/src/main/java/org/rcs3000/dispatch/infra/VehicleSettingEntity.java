package org.rcs3000.dispatch.infra;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("vehicle_dispatch_setting")
public class VehicleSettingEntity {

    @TableId
    private String vehicleCode;
    private Boolean accepting;
    private Instant updatedAt;

    public String getVehicleCode() {
        return vehicleCode;
    }

    public void setVehicleCode(String vehicleCode) {
        this.vehicleCode = vehicleCode;
    }

    public Boolean getAccepting() {
        return accepting;
    }

    public void setAccepting(Boolean accepting) {
        this.accepting = accepting;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
