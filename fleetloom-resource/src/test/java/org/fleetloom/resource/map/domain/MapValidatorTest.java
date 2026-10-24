package org.fleetloom.resource.map.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fleetloom.resource.map.domain.MapFixtures.oneWay;
import static org.fleetloom.resource.map.domain.MapFixtures.point;
import static org.fleetloom.resource.map.domain.MapFixtures.twoWay;
import static org.fleetloom.resource.map.domain.MapFixtures.validSquare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.fleetloom.contracts.map.AreaType;
import org.fleetloom.contracts.map.PathDirection;
import org.fleetloom.contracts.map.PathShape;
import org.fleetloom.contracts.map.PointType;
import org.fleetloom.contracts.map.VehicleOrientation;

class MapValidatorTest {

    private final MapValidator validator = new MapValidator();

    @Test
    void validMapHasNoIssues() {
        assertThat(validator.validate(validSquare()).issues()).isEmpty();
    }

    @Test
    void emptyMapIsAnError() {
        ValidationReport report = validator.validate(MapContent.empty());

        assertThat(report.hasErrors()).isTrue();
        assertThat(rules(report)).containsExactly("EMPTY_MAP");
    }

    @Test
    void duplicateCodesAreReportedOncePerCodeAndKind() {
        MapContent content = new MapContent(
                List.of(point("A", 0, 0), point("A", 5, 0), point("A", 9, 0), point("B", 1, 1)),
                List.of(twoWay("P", "A", "B"), twoWay("P", "B", "A")),
                List.of());

        ValidationReport report = validator.validate(content);

        assertThat(report.errors()).filteredOn(i -> i.rule().equals("DUPLICATE_CODE"))
                .extracting(ValidationIssue::elementCode)
                .containsExactly("A", "P");
    }

    @Test
    void blankCodeIsAnError() {
        MapContent content = withPoints(point(" ", 50, 50));

        assertThat(rules(validator.validate(content))).contains("BLANK_CODE");
    }

    @Test
    void pathToUnknownPointIsAnError() {
        MapContent square = validSquare();
        MapContent content = new MapContent(square.points(), append(square.paths(), twoWay("AX", "A", "X")), List.of());

        ValidationReport report = validator.validate(content);

        assertThat(report.errors()).anySatisfy(i -> {
            assertThat(i.rule()).isEqualTo("PATH_UNKNOWN_POINT");
            assertThat(i.elementCode()).isEqualTo("AX");
            assertThat(i.message()).contains("'X'");
        });
    }

    @Test
    void selfLoopIsAnError() {
        MapContent square = validSquare();
        MapContent content = new MapContent(square.points(), append(square.paths(), oneWay("AA", "A", "A")), List.of());

        assertThat(errorRules(validator.validate(content))).contains("PATH_SELF_LOOP");
    }

    @Test
    void twoPathsInTheSameDirectionAreDuplicates() {
        MapContent content = new MapContent(
                List.of(point("A", 0, 0), point("B", 10, 0)),
                List.of(oneWay("AB1", "A", "B"), oneWay("AB2", "A", "B")),
                List.of());

        assertThat(validator.validate(content).errors())
                .anySatisfy(i -> assertThat(i).extracting(ValidationIssue::rule, ValidationIssue::elementCode)
                        .containsExactly("DUPLICATE_PATH", "AB2"));
    }

    @Test
    void bidirectionalPathConflictsWithOneWayInEitherDirection() {
        MapContent content = new MapContent(
                List.of(point("A", 0, 0), point("B", 10, 0)),
                List.of(twoWay("AB", "A", "B"), oneWay("BA", "B", "A")),
                List.of());

        assertThat(errorRules(validator.validate(content))).contains("DUPLICATE_PATH");
    }

    @Test
    void opposingOneWayPathsAreAllowed() {
        MapContent content = new MapContent(
                List.of(point("A", 0, 0), point("B", 10, 0)),
                List.of(oneWay("AB", "A", "B"), oneWay("BA", "B", "A")),
                List.of());

        assertThat(validator.validate(content).hasErrors()).isFalse();
    }

    @Test
    void bezierNeedsExactlyTwoControlPoints() {
        MapPath curve = new MapPath("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.BEZIER,
                List.of(new Position(5, 5)), null, VehicleOrientation.ANY, Set.of(), 1.0, true);
        MapContent content = new MapContent(List.of(point("A", 0, 0), point("B", 10, 0)), List.of(curve), List.of());

        assertThat(errorRules(validator.validate(content))).containsExactly("INVALID_CONTROL_POINTS");
    }

    @Test
    void straightPathMustNotHaveControlPoints() {
        MapPath line = new MapPath("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.LINE,
                List.of(new Position(5, 5)), null, VehicleOrientation.ANY, Set.of(), 1.0, true);
        MapContent content = new MapContent(List.of(point("A", 0, 0), point("B", 10, 0)), List.of(line), List.of());

        assertThat(errorRules(validator.validate(content))).containsExactly("INVALID_CONTROL_POINTS");
    }

    @Test
    void nonPositiveSpeedAndWeightAreErrors() {
        MapPath path = new MapPath("AB", "A", "B", PathDirection.BIDIRECTIONAL, PathShape.LINE,
                List.of(), 0.0, VehicleOrientation.ANY, Set.of(), -1, true);
        MapContent content = new MapContent(List.of(point("A", 0, 0), point("B", 10, 0)), List.of(path), List.of());

        assertThat(errorRules(validator.validate(content)))
                .containsExactly("INVALID_PATH_PARAMETER", "INVALID_PATH_PARAMETER");
    }

    @Test
    void missingRequiredFieldsAreErrors() {
        MapPath path = new MapPath("AB", "A", "B", null, PathShape.LINE, List.of(), null, VehicleOrientation.ANY,
                Set.of(), 1.0, true);
        MapPoint untyped = new MapPoint("A", 0, 0, null, null, Map.of());
        MapContent content = new MapContent(List.of(untyped, point("B", 10, 0)), List.of(path), List.of());

        assertThat(validator.validate(content).errors())
                .filteredOn(i -> i.rule().equals("MISSING_FIELD"))
                .extracting(ValidationIssue::elementCode)
                .containsExactlyInAnyOrder("A", "AB");
    }

    @Test
    void nonFiniteCoordinateIsAnError() {
        MapContent content = withPoints(point("N", Double.NaN, 0));

        assertThat(errorRules(validator.validate(content))).contains("INVALID_COORDINATE");
    }

    @Test
    void areaNeedsAtLeastThreeVertices() {
        MapArea area = new MapArea("Z", AreaType.MUTEX, List.of(new Position(0, 0), new Position(1, 1)), Map.of());
        MapContent square = validSquare();
        MapContent content = new MapContent(square.points(), square.paths(), List.of(area));

        assertThat(errorRules(validator.validate(content))).containsExactly("INVALID_POLYGON");
    }

    @Test
    void isolatedPointIsOnlyAWarning() {
        ValidationReport report = validator.validate(withPoints(point("LONELY", 50, 50)));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.warnings()).extracting(ValidationIssue::rule, ValidationIssue::elementCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("ISOLATED_POINT", "LONELY"));
    }

    @Test
    void disabledPathsDoNotConnectPoints() {
        MapContent square = validSquare();
        List<MapPath> paths = new ArrayList<>(square.paths());
        MapPath ae = paths.removeLast();
        paths.add(new MapPath(ae.code(), ae.fromPoint(), ae.toPoint(), ae.direction(), ae.shape(), ae.controlPoints(),
                ae.maxSpeed(), ae.orientation(), ae.allowedVehicleTypes(), ae.weightFactor(), false));

        ValidationReport report = validator.validate(new MapContent(square.points(), paths, List.of()));

        assertThat(report.warnings()).extracting(ValidationIssue::elementCode).containsExactly("E");
    }

    @Test
    void chargingPointReachableOnlyOneWayIsWarned() {
        MapContent square = validSquare();
        List<MapPath> paths = new ArrayList<>(square.paths());
        paths.removeLast();
        paths.add(oneWay("AE", "A", "E"));

        ValidationReport report = validator.validate(new MapContent(square.points(), paths, List.of()));

        assertThat(report.hasErrors()).isFalse();
        assertThat(report.warnings()).extracting(ValidationIssue::rule, ValidationIssue::elementCode)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("UNREACHABLE_POINT", "E"));
    }

    @Test
    void ordinaryDeadEndPointIsNotWarned() {
        MapContent square = validSquare();
        MapContent content = new MapContent(append(square.points(), point("F", 20, 0)),
                append(square.paths(), oneWay("BF", "B", "F")), List.of());

        assertThat(validator.validate(content).issues()).isEmpty();
    }

    @Test
    void overlappingPointsAreWarned() {
        MapContent square = validSquare();
        MapContent content = new MapContent(append(square.points(), point("A2", 0.005, 0)),
                append(square.paths(), twoWay("AA2", "A2", "B")), List.of());

        assertThat(validator.validate(content).warnings())
                .extracting(ValidationIssue::rule, ValidationIssue::elementCode)
                .contains(org.assertj.core.groups.Tuple.tuple("OVERLAPPING_POINTS", "A2"));
    }

    @Test
    void largestComponentHandlesLongChainsWithoutRecursion() {
        int n = 50_000;
        Map<String, List<String>> adjacency = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            adjacency.put("P" + i, List.of("P" + ((i + 1) % n)));
        }

        assertThat(MapValidator.largestStronglyConnectedComponent(adjacency)).hasSize(n);
    }

    @Test
    void largestComponentPicksTheBiggestCycle() {
        Map<String, List<String>> adjacency = Map.of(
                "A", List.of("B"), "B", List.of("A"),
                "C", List.of("D"), "D", List.of("E"), "E", List.of("C", "A"));

        assertThat(MapValidator.largestStronglyConnectedComponent(adjacency)).containsExactlyInAnyOrder("C", "D", "E");
    }

    private static MapContent withPoints(MapPoint... extra) {
        MapContent square = validSquare();
        return new MapContent(append(square.points(), extra), square.paths(), List.of());
    }

    @SafeVarargs
    private static <T> List<T> append(List<T> list, T... extra) {
        List<T> result = new ArrayList<>(list);
        result.addAll(List.of(extra));
        return result;
    }

    private static List<String> rules(ValidationReport report) {
        return report.issues().stream().map(ValidationIssue::rule).toList();
    }

    private static List<String> errorRules(ValidationReport report) {
        return report.errors().stream().map(ValidationIssue::rule).toList();
    }

    @Test
    void pointTypesOutsideDestinationsAreNotCheckedForReachability() {
        MapContent square = validSquare();
        MapContent content = new MapContent(append(square.points(), point("R", 20, 10, PointType.ROTATION)),
                append(square.paths(), oneWay("CR", "C", "R")), List.of());

        assertThat(validator.validate(content).issues()).isEmpty();
    }
}
