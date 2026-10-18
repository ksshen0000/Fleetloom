package org.rcs3000.resource.vehicle.infrastructure;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("vehicle")
public class VehicleEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String name;
    private String typeCode;
    private String manufacturer;
    private String serialNumber;
    private Boolean simulated;
    private Boolean enabled;
    private String initialPoint;
    private Double chargeThresholdLow;
    private Double chargeThresholdHigh;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTypeCode() {
        return typeCode;
    }

    public void setTypeCode(String typeCode) {
        this.typeCode = typeCode;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getSerialNumber() {
        return serialNumber;
    }

    public void setSerialNumber(String serialNumber) {
        this.serialNumber = serialNumber;
    }

    public Boolean getSimulated() {
        return simulated;
    }

    public void setSimulated(Boolean simulated) {
        this.simulated = simulated;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getInitialPoint() {
        return initialPoint;
    }

    public void setInitialPoint(String initialPoint) {
        this.initialPoint = initialPoint;
    }

    public Double getChargeThresholdLow() {
        return chargeThresholdLow;
    }

    public void setChargeThresholdLow(Double chargeThresholdLow) {
        this.chargeThresholdLow = chargeThresholdLow;
    }

    public Double getChargeThresholdHigh() {
        return chargeThresholdHigh;
    }

    public void setChargeThresholdHigh(Double chargeThresholdHigh) {
        this.chargeThresholdHigh = chargeThresholdHigh;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
