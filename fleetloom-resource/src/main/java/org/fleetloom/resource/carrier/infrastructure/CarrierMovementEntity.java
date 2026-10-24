package org.fleetloom.resource.carrier.infrastructure;

import java.time.Instant;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("carrier_movement")
public class CarrierMovementEntity {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String carrierCode;
    private String fromType;
    private String fromRef;
    private String toType;
    private String toRef;
    private String orderRef;
    private Instant movedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public void setCarrierCode(String carrierCode) {
        this.carrierCode = carrierCode;
    }

    public String getFromType() {
        return fromType;
    }

    public void setFromType(String fromType) {
        this.fromType = fromType;
    }

    public String getFromRef() {
        return fromRef;
    }

    public void setFromRef(String fromRef) {
        this.fromRef = fromRef;
    }

    public String getToType() {
        return toType;
    }

    public void setToType(String toType) {
        this.toType = toType;
    }

    public String getToRef() {
        return toRef;
    }

    public void setToRef(String toRef) {
        this.toRef = toRef;
    }

    public String getOrderRef() {
        return orderRef;
    }

    public void setOrderRef(String orderRef) {
        this.orderRef = orderRef;
    }

    public Instant getMovedAt() {
        return movedAt;
    }

    public void setMovedAt(Instant movedAt) {
        this.movedAt = movedAt;
    }
}
