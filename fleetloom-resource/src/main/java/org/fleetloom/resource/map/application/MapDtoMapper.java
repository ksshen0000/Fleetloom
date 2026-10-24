package org.fleetloom.resource.map.application;

import java.util.List;
import java.util.Map;

import org.fleetloom.contracts.map.AreaDto;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.map.PathDto;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.PointDto;
import org.fleetloom.contracts.map.PositionDto;
import org.fleetloom.contracts.map.VehicleOrientation;
import org.fleetloom.resource.map.domain.MapArea;
import org.fleetloom.resource.map.domain.MapContent;
import org.fleetloom.resource.map.domain.MapPath;
import org.fleetloom.resource.map.domain.MapPoint;
import org.fleetloom.resource.map.domain.MapVersion;
import org.fleetloom.resource.map.domain.PathGeometry;
import org.fleetloom.resource.map.domain.Position;

/** Converts between map domain values and the published contract DTOs. */
public final class MapDtoMapper {

    private MapDtoMapper() {
    }

    /** Builds domain content from client input, applying defaults for omitted optional fields. */
    public static MapContent toContent(List<PointDto> points, List<PathDto> paths, List<AreaDto> areas) {
        return new MapContent(
                nullToEmpty(points).stream().map(MapDtoMapper::toPoint).toList(),
                nullToEmpty(paths).stream().map(MapDtoMapper::toPath).toList(),
                nullToEmpty(areas).stream().map(MapDtoMapper::toArea).toList());
    }

    public static MapTopologyDto toTopology(String mapCode, MapVersion version) {
        MapContent content = version.getContent();
        return new MapTopologyDto(mapCode, version.getVersionNo(),
                toPointDtos(content), toPathDtos(content), toAreaDtos(content));
    }

    public static List<PointDto> toPointDtos(MapContent content) {
        return content.points().stream()
                .map(p -> new PointDto(p.code(), p.x(), p.y(), p.theta(), p.type(), p.properties()))
                .toList();
    }

    /** Path DTOs with computed lengths; a path with an unknown endpoint gets length 0. */
    public static List<PathDto> toPathDtos(MapContent content) {
        Map<String, MapPoint> points = content.pointsByCode();
        return content.paths().stream().map(p -> {
            MapPoint from = points.get(p.fromPoint());
            MapPoint to = points.get(p.toPoint());
            double length = from == null || to == null ? 0 : PathGeometry.length(p, from.position(), to.position());
            return new PathDto(p.code(), p.fromPoint(), p.toPoint(), p.direction(), p.shape(),
                    p.controlPoints().stream().map(MapDtoMapper::toPositionDto).toList(),
                    p.maxSpeed(), p.orientation(), p.allowedVehicleTypes(), p.weightFactor(), p.enabled(), length);
        }).toList();
    }

    public static List<AreaDto> toAreaDtos(MapContent content) {
        return content.areas().stream()
                .map(a -> new AreaDto(a.code(), a.type(),
                        a.polygon().stream().map(MapDtoMapper::toPositionDto).toList(), a.properties()))
                .toList();
    }

    private static MapPoint toPoint(PointDto dto) {
        return new MapPoint(dto.code(), dto.x(), dto.y(), dto.theta(), dto.type(), dto.properties());
    }

    private static MapPath toPath(PathDto dto) {
        return new MapPath(dto.code(), dto.fromPoint(), dto.toPoint(), dto.direction(),
                dto.shape() == null ? PathShape.LINE : dto.shape(),
                nullToEmpty(dto.controlPoints()).stream().map(MapDtoMapper::toPosition).toList(),
                dto.maxSpeed(),
                dto.orientation() == null ? VehicleOrientation.ANY : dto.orientation(),
                dto.allowedVehicleTypes(),
                dto.weightFactor() == null ? 1.0 : dto.weightFactor(),
                dto.enabled() == null || dto.enabled());
    }

    private static MapArea toArea(AreaDto dto) {
        return new MapArea(dto.code(), dto.type(),
                nullToEmpty(dto.polygon()).stream().map(MapDtoMapper::toPosition).toList(), dto.properties());
    }

    private static Position toPosition(PositionDto dto) {
        return new Position(dto.x(), dto.y());
    }

    private static PositionDto toPositionDto(Position position) {
        return new PositionDto(position.x(), position.y());
    }

    private static <T> List<T> nullToEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
