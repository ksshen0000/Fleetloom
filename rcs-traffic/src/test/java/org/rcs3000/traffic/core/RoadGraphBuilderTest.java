package org.rcs3000.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.rcs3000.contracts.map.AreaType;
import org.rcs3000.contracts.map.MapTopologyDto;
import org.rcs3000.contracts.map.PathDirection;
import org.rcs3000.contracts.map.PathDto;
import org.rcs3000.contracts.map.PathShape;
import org.rcs3000.contracts.map.PositionDto;
import org.rcs3000.contracts.map.VehicleOrientation;

class RoadGraphBuilderTest {

    @Test
    void bidirectionalPathBecomesTwoEdgesSharingTheResource() {
        RoadGraph g = RoadGraphBuilder.build(TestMaps.corridor());

        assertThat(g.edge("A", "B")).isPresent();
        assertThat(g.edge("B", "A")).isPresent();
        assertThat(Resource.of(g.edge("A", "B").get())).isEqualTo(Resource.of(g.edge("B", "A").get()));
    }

    @Test
    void oneWayPathHasOneEdge() {
        MapTopologyDto map = new MapTopologyDto("M", 1, List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 1, 0)),
                List.of(TestMaps.path("AB", "A", "B", PathDirection.UNIDIRECTIONAL, 1)), List.of());

        RoadGraph g = RoadGraphBuilder.build(map);

        assertThat(g.edge("A", "B")).isPresent();
        assertThat(g.edge("B", "A")).isEmpty();
    }

    @Test
    void disabledPathsAndForbiddenAreasAreLeftOut() {
        MapTopologyDto map = new MapTopologyDto("M", 1,
                List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 4, 0), TestMaps.point("X", 4, 4)),
                List.of(new PathDto("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.LINE, List.of(), null,
                                VehicleOrientation.ANY, null, 1.0, false, 4.0),
                        TestMaps.path("BX", "B", "X", PathDirection.BIDIRECTIONAL, 4)),
                List.of(TestMaps.rectangle("NO", AreaType.FORBIDDEN, 3, 3, 5, 5)));

        RoadGraph g = RoadGraphBuilder.build(map);

        assertThat(g.edge("A", "B")).isEmpty();
        assertThat(g.contains("X")).isFalse();
        assertThat(g.outgoing("B")).isEmpty();
    }

    @Test
    void mutexAreasAreAttachedToNodesAndEdges() {
        RoadGraph g = RoadGraphBuilder.build(TestMaps.grid(1, 3, 4,
                List.of(TestMaps.rectangle("Z", AreaType.MUTEX, 3, -1, 5, 1))));

        assertThat(g.node("P01").get().mutexAreas()).containsExactly("Z");
        assertThat(g.node("P00").get().mutexAreas()).isEmpty();
        assertThat(g.edge("P00", "P01").get().mutexAreas()).containsExactly("Z");
    }

    @Test
    void reverseOfACurveHasReversedControlPointsAndHeadings() {
        MapTopologyDto map = new MapTopologyDto("M", 1, List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 10, 10)),
                List.of(new PathDto("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.BEZIER,
                        List.of(new PositionDto(10, 0), new PositionDto(10, 10)), null, VehicleOrientation.ANY, null, 1.0,
                        true, 15.0)),
                List.of());

        RoadGraph g = RoadGraphBuilder.build(map);

        RoadGraph.Edge forward = g.edge("A", "B").get();
        RoadGraph.Edge back = g.edge("B", "A").get();
        assertThat(forward.length()).isEqualTo(15.0);
        assertThat(forward.startHeading()).isEqualTo(0.0);
        assertThat(back.controlPoints()).extracting(PositionDto::y).containsExactly(10.0, 0.0);
        assertThat(back.endHeading()).isEqualTo(Math.PI);
    }

    @Test
    void pointInPolygon() {
        List<PositionDto> square = TestMaps.rectangle("S", AreaType.MUTEX, 0, 0, 2, 2).polygon();

        assertThat(RoadGraphBuilder.contains(square, 1, 1)).isTrue();
        assertThat(RoadGraphBuilder.contains(square, 3, 1)).isFalse();
        assertThat(Map.of()).isEmpty();
    }
}
