package org.fleetloom.dispatch.infra;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/** Row of order_template; {@code viaPoints} is a JSON array of point codes. */
@TableName("order_template")
public class OrderTemplateEntity {

    @TableId
    private String code;
    private String name;
    private String pickAction;
    private String dropAction;
    private String viaPoints;
    private String viaAction;
    private String description;

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

    public String getPickAction() {
        return pickAction;
    }

    public void setPickAction(String pickAction) {
        this.pickAction = pickAction;
    }

    public String getDropAction() {
        return dropAction;
    }

    public void setDropAction(String dropAction) {
        this.dropAction = dropAction;
    }

    public String getViaPoints() {
        return viaPoints;
    }

    public void setViaPoints(String viaPoints) {
        this.viaPoints = viaPoints;
    }

    public String getViaAction() {
        return viaAction;
    }

    public void setViaAction(String viaAction) {
        this.viaAction = viaAction;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
