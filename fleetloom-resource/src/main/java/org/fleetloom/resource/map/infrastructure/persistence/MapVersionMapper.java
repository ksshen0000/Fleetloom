package org.fleetloom.resource.map.infrastructure.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MapVersionMapper extends BaseMapper<MapVersionEntity> {

    @Select("SELECT COALESCE(MAX(version_no), 0) FROM map_version WHERE map_id = #{mapId}")
    int maxVersionNo(@Param("mapId") Long mapId);
}
