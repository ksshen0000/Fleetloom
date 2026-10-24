package org.fleetloom.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.jgrapht.alg.shortestpath.DijkstraShortestPath;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.SimpleDirectedWeightedGraph;
import org.junit.jupiter.api.Test;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathDto;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.PointDto;
import org.fleetloom.contracts.map.VehicleOrientation;

class AStarPlannerTest {

    private final RoadGraph grid = RoadGraphBuilder.build(TestMaps.grid(3, 3, 4));

    @Test
    void findsTheStraightRoute() {
        PlannedRoute route = new AStarPlanner(2).plan(grid, PathPlanner.Request.simple("P10", "P12", 1.0)).orElseThrow();

        assertThat(route.nodes()).containsExactly("P10", "P11", "P12");
        assertThat(route.length()).isEqualTo(8.0);
        assertThat(route.seconds()).isCloseTo(8.0, within(1e-9));
    }

    @Test
    void turnPenaltyPrefersFewerTurns() {
        PlannedRoute route = new AStarPlanner(2).plan(grid, PathPlanner.Request.simple("P00", "P22", 1.0)).orElseThrow();

        assertThat(route.length()).isEqualTo(16.0);
        assertThat(turns(route)).isEqualTo(1);
        assertThat(route.seconds()).isCloseTo(16 + Math.PI / 2 * 2, within(1e-9));
    }

    @Test
    void respectsOneWayPaths() {
        MapTopologyDto map = new MapTopologyDto("M", 1, List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 1, 0)),
                List.of(TestMaps.path("AB", "A", "B", PathDirection.UNIDIRECTIONAL, 1)), List.of());
        RoadGraph g = RoadGraphBuilder.build(map);

        assertThat(new AStarPlanner(2).plan(g, PathPlanner.Request.simple("A", "B", 1))).isPresent();
        assertThat(new AStarPlanner(2).plan(g, PathPlanner.Request.simple("B", "A", 1))).isEmpty();
    }

    @Test
    void avoidsBlockedResources() {
        PlannedRoute route = new AStarPlanner(2).plan(grid,
                new PathPlanner.Request("P10", "P12", 1.0, Set.of(Resource.node("P11")), Map.of())).orElseThrow();

        assertThat(route.nodes()).doesNotContain("P11");
        assertThat(route.length()).isEqualTo(16.0);
    }

    @Test
    void blockedTargetIsUnreachable() {
        assertThat(new AStarPlanner(2).plan(grid,
                new PathPlanner.Request("P10", "P12", 1.0, Set.of(Resource.node("P12")), Map.of()))).isEmpty();
    }

    @Test
    void penaltiesSteerAroundOccupiedPoints() {
        PlannedRoute route = new AStarPlanner(2).plan(grid,
                new PathPlanner.Request("P10", "P12", 1.0, Set.of(), Map.of(Resource.node("P11"), 30.0))).orElseThrow();

        assertThat(route.nodes()).doesNotContain("P11");
    }

    @Test
    void edgeSpeedLimitAndWeightCountInTheCost() {
        MapTopologyDto map = new MapTopologyDto("M", 1, List.of(TestMaps.point("A", 0, 0), TestMaps.point("B", 4, 0)),
                List.of(new PathDto("AB", "A", "B", PathDirection.UNIDIRECTIONAL, PathShape.LINE, List.of(), 0.5,
                        VehicleOrientation.ANY, null, 2.0, true, 4.0)), List.of());

        PlannedRoute route = new AStarPlanner(2).plan(RoadGraphBuilder.build(map), PathPlanner.Request.simple("A", "B", 1.0))
                .orElseThrow();

        assertThat(route.seconds()).isEqualTo(4 / 0.5 * 2.0);
    }

    @Test
    void startEqualsTargetIsATrivialRoute() {
        assertThat(new AStarPlanner(2).plan(grid, PathPlanner.Request.simple("P11", "P11", 1)).orElseThrow().nodes())
                .containsExactly("P11");
    }

    /** Without turn penalties A* must find exactly the Dijkstra optimum. */
    @Test
    void matchesDijkstraOnRandomWeightedGrids() {
        Random random = new Random(42);
        for (int trial = 0; trial < 5; trial++) {
            MapTopologyDto map = randomGrid(random, 8, 8);
            RoadGraph g = RoadGraphBuilder.build(map);
            SimpleDirectedWeightedGraph<String, DefaultWeightedEdge> reference = toJGraphT(g);
            AStarPlanner planner = new AStarPlanner(0);
            for (int i = 0; i < 40; i++) {
                String from = TestMaps.name(random.nextInt(8), random.nextInt(8));
                String to = TestMaps.name(random.nextInt(8), random.nextInt(8));
                var expected = DijkstraShortestPath.findPathBetween(reference, from, to);
                var actual = planner.plan(g, PathPlanner.Request.simple(from, to, 1.0));
                if (expected == null) {
                    assertThat(actual).isEmpty();
                } else {
                    assertThat(actual).hasValueSatisfying(r -> assertThat(r.seconds()).isCloseTo(expected.getWeight(), within(1e-9)));
                }
            }
        }
    }

    private static MapTopologyDto randomGrid(Random random, int rows, int cols) {
        List<PointDto> points = new ArrayList<>();
        List<PathDto> paths = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                points.add(TestMaps.point(TestMaps.name(r, c), c * 2.0, r * 2.0));
                for (int[] d : new int[][] {{0, 1}, {1, 0}}) {
                    int r2 = r + d[0];
                    int c2 = c + d[1];
                    if (r2 < rows && c2 < cols && random.nextDouble() < 0.85) {
                        PathDirection direction = random.nextDouble() < 0.2 ? PathDirection.UNIDIRECTIONAL : PathDirection.BIDIRECTIONAL;
                        String a = TestMaps.name(r, c);
                        String b = TestMaps.name(r2, c2);
                        boolean flip = random.nextBoolean();
                        paths.add(new PathDto(a + "-" + b, flip ? b : a, flip ? a : b, direction, PathShape.LINE, List.of(),
                                null, VehicleOrientation.ANY, null, (double) (1 + random.nextInt(3)), true, 2.0));
                    }
                }
            }
        }
        return new MapTopologyDto("R", 1, points, paths, List.of());
    }

    private static SimpleDirectedWeightedGraph<String, DefaultWeightedEdge> toJGraphT(RoadGraph g) {
        SimpleDirectedWeightedGraph<String, DefaultWeightedEdge> graph = new SimpleDirectedWeightedGraph<>(DefaultWeightedEdge.class);
        g.nodes().forEach(n -> graph.addVertex(n.code()));
        for (RoadGraph.Node n : g.nodes()) {
            for (RoadGraph.Edge e : g.outgoing(n.code())) {
                graph.setEdgeWeight(graph.addEdge(e.from(), e.to()), AStarPlanner.edgeCost(e, PathPlanner.Request.simple("", "", 1.0)));
            }
        }
        return graph;
    }

    private static int turns(PlannedRoute route) {
        int turns = 0;
        for (int i = 1; i < route.edges().size(); i++) {
            if (Math.abs(route.edges().get(i).startHeading() - route.edges().get(i - 1).endHeading()) > 1e-9) {
                turns++;
            }
        }
        return turns;
    }
}
