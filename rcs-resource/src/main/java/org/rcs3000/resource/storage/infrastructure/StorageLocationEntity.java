package org.rcs3000.resource.storage.infrastructure;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Row of storage_location; sets are stored comma-separated. */
@TableName("storage_location")
public class StorageLocationEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String code;
    private String zoneCode;
    private String type;
    private String mapCode;
    private String accessPoint;
    private Double accessTheta;
    private Integer level;
    private Double height;
    private String allowedCarrierTypes;
    private String status;
    private String statusBeforeDisable;
    private String lockOwner;
    private String carrierCode;
    private Integer version;
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

    public String getZoneCode() {
        return zoneCode;
    }

    public void setZoneCode(String zoneCode) {
        this.zoneCode = zoneCode;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getMapCode() {
        return mapCode;
    }

    public void setMapCode(String mapCode) {
        this.mapCode = mapCode;
    }

    public String getAccessPoint() {
        return accessPoint;
    }

    public void setAccessPoint(String accessPoint) {
        this.accessPoint = accessPoint;
    }

    public Double getAccessTheta() {
        return accessTheta;
    }

    public void setAccessTheta(Double accessTheta) {
        this.accessTheta = accessTheta;
    }

    public Integer getLevel() {
        return level;
    }

    public void setLevel(Integer level) {
        this.level = level;
    }

    public Double getHeight() {
        return height;
    }

    public void setHeight(Double height) {
        this.height = height;
    }

    public String getAllowedCarrierTypes() {
        return allowedCarrierTypes;
    }

    public void setAllowedCarrierTypes(String allowedCarrierTypes) {
        this.allowedCarrierTypes = allowedCarrierTypes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getStatusBeforeDisable() {
        return statusBeforeDisable;
    }

    public void setStatusBeforeDisable(String statusBeforeDisable) {
        this.statusBeforeDisable = statusBeforeDisable;
    }

    public String getLockOwner() {
        return lockOwner;
    }

    public void setLockOwner(String lockOwner) {
        this.lockOwner = lockOwner;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public void setCarrierCode(String carrierCode) {
        this.carrierCode = carrierCode;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
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
