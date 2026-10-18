package org.rcs3000.resource.map.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.rcs3000.contracts.map.PathShape;
import org.rcs3000.contracts.map.PointType;

/**
 * Checks a map version before it is published (requirement MAP-06). Errors block publishing,
 * warnings do not.
 */
public final class MapValidator {

    /** Points closer than this (meters) are reported as overlapping. */
    static final double OVERLAP_TOLERANCE = 0.01;

    /** Point types that vehicles are sent to and must therefore be mutually reachable with the network. */
    private static final Set<PointType> DESTINATION_TYPES =
            EnumSet.of(PointType.WORKSTATION, PointType.CHARGING, PointType.PARKING, PointType.WAITING);

    public ValidationReport validate(MapContent content) {
        List<ValidationIssue> issues = new ArrayList<>();
        if (content.points().isEmpty()) {
            issues.add(ValidationIssue.error("EMPTY_MAP", null, "map has no points"));
            return new ValidationReport(issues);
        }
        checkCodes(content.points(), MapPoint::code, "point", issues);
        checkCodes(content.paths(), MapPath::code, "path", issues);
        checkCodes(content.areas(), MapArea::code, "area", issues);
        content.points().forEach(p -> checkPoint(p, issues));
        Map<String, MapPoint> points = content.pointsByCode();
        content.paths().forEach(p -> checkPath(p, points, issues));
        checkDuplicateEdges(content.paths(), issues);
        content.areas().forEach(a -> checkArea(a, issues));
        checkOverlappingPoints(content.points(), issues);
        checkConnectivity(content, issues);
        return new ValidationReport(issues);
    }

    private static <T> void checkCodes(List<T> elements, Function<T, String> code, String kind,
            List<ValidationIssue> issues) {
        Set<String> seen = new HashSet<>();
        Set<String> reported = new HashSet<>();
        for (T element : elements) {
            String c = code.apply(element);
            if (c == null || c.isBlank()) {
                issues.add(ValidationIssue.error("BLANK_CODE", null, "a %s has no code".formatted(kind)));
            } else if (!seen.add(c) && reported.add(c)) {
                issues.add(ValidationIssue.error("DUPLICATE_CODE", c, "%s code '%s' is used more than once".formatted(kind, c)));
            }
        }
    }

    private static void checkPoint(MapPoint point, List<ValidationIssue> issues) {
        if (point.type() == null) {
            issues.add(ValidationIssue.error("MISSING_FIELD", point.code(), "point has no type"));
        }
        boolean thetaInvalid = point.theta() != null && !Double.isFinite(point.theta());
        if (!Double.isFinite(point.x()) || !Double.isFinite(point.y()) || thetaInvalid) {
            issues.add(ValidationIssue.error("INVALID_COORDINATE", point.code(), "point has a non-finite coordinate"));
        }
    }

    private static void checkPath(MapPath path, Map<String, MapPoint> points, List<ValidationIssue> issues) {
        String code = path.code();
        if (path.direction() == null || path.shape() == null || path.orientation() == null) {
            issues.add(ValidationIssue.error("MISSING_FIELD", code, "path requires direction, shape and orientation"));
        }
        for (String end : new String[] {path.fromPoint(), path.toPoint()}) {
            if (end == null || !points.containsKey(end)) {
                issues.add(ValidationIssue.error("PATH_UNKNOWN_POINT", code, "path references unknown point '%s'".formatted(end)));
            }
        }
        if (path.fromPoint() != null && path.fromPoint().equals(path.toPoint())) {
            issues.add(ValidationIssue.error("PATH_SELF_LOOP", code, "path starts and ends at the same point"));
        }
        int controlPoints = path.controlPoints().size();
        if (path.shape() == PathShape.BEZIER && controlPoints != 2) {
            issues.add(ValidationIssue.error("INVALID_CONTROL_POINTS", code,
                    "a Bezier path needs exactly 2 control points, found %d".formatted(controlPoints)));
        } else if (path.shape() == PathShape.LINE && controlPoints != 0) {
            issues.add(ValidationIssue.error("INVALID_CONTROL_POINTS", code, "a straight path must not have control points"));
        }
        if (path.maxSpeed() != null && !(path.maxSpeed() > 0)) {
            issues.add(ValidationIssue.error("INVALID_PATH_PARAMETER", code, "maxSpeed must be positive"));
        }
        if (!(path.weightFactor() > 0) || !Double.isFinite(path.weightFactor())) {
            issues.add(ValidationIssue.error("INVALID_PATH_PARAMETER", code, "weightFactor must be positive"));
        }
    }

    /** Two paths must not allow travel in the same direction between the same pair of points. */
    private static void checkDuplicateEdges(List<MapPath> paths, List<ValidationIssue> issues) {
        Map<String, String> edgeOwner = new HashMap<>();
        for (MapPath path : paths) {
            if (path.fromPoint() == null || path.toPoint() == null) {
                continue;
            }
            List<String> edges = new ArrayList<>();
            edges.add(path.fromPoint() + "->" + path.toPoint());
            if (path.bidirectional()) {
                edges.add(path.toPoint() + "->" + path.fromPoint());
            }
            for (String edge : edges) {
                String owner = edgeOwner.putIfAbsent(edge, path.code());
                if (owner != null && !owner.equals(path.code())) {
                    issues.add(ValidationIssue.error("DUPLICATE_PATH", path.code(),
                            "path duplicates the direction %s of path '%s'".formatted(edge, owner)));
                    break;
                }
            }
        }
    }

    private static void checkArea(MapArea area, List<ValidationIssue> issues) {
        if (area.type() == null) {
            issues.add(ValidationIssue.error("MISSING_FIELD", area.code(), "area has no type"));
        }
        if (area.polygon().size() < 3) {
            issues.add(ValidationIssue.error("INVALID_POLYGON", area.code(), "area polygon needs at least 3 vertices"));
        }
    }

    private static void checkOverlappingPoints(List<MapPoint> points, List<ValidationIssue> issues) {
        for (int i = 0; i < points.size(); i++) {
            for (int j = i + 1; j < points.size(); j++) {
                MapPoint a = points.get(i);
                MapPoint b = points.get(j);
                if (a.position().distanceTo(b.position()) < OVERLAP_TOLERANCE) {
                    issues.add(ValidationIssue.warning("OVERLAPPING_POINTS", b.code(),
                            "point overlaps point '%s'".formatted(a.code())));
                }
            }
        }
    }

    /**
     * Warns about points no enabled path touches, and about destination points that are not in the
     * largest strongly connected component (a vehicle could get there but not back, or not at all).
     */
    private static void checkConnectivity(MapContent content, List<ValidationIssue> issues) {
        Map<String, MapPoint> points = content.pointsByCode();
        Map<String, List<String>> adjacency = new HashMap<>();
        points.keySet().forEach(code -> adjacency.put(code, new ArrayList<>()));
        Set<String> touched = new HashSet<>();
        for (MapPath path : content.paths()) {
            if (!path.enabled() || !points.containsKey(path.fromPoint()) || !points.containsKey(path.toPoint())) {
                continue;
            }
            adjacency.get(path.fromPoint()).add(path.toPoint());
            if (path.bidirectional()) {
                adjacency.get(path.toPoint()).add(path.fromPoint());
            }
            touched.add(path.fromPoint());
            touched.add(path.toPoint());
        }
        for (MapPoint point : points.values()) {
            if (!touched.contains(point.code())) {
                issues.add(ValidationIssue.warning("ISOLATED_POINT", point.code(), "no enabled path connects to this point"));
            }
        }
        if (touched.isEmpty()) {
            return;
        }
        Set<String> mainComponent = largestStronglyConnectedComponent(adjacency);
        for (MapPoint point : points.values()) {
            if (touched.contains(point.code()) && DESTINATION_TYPES.contains(point.type())
                    && !mainComponent.contains(point.code())) {
                issues.add(ValidationIssue.warning("UNREACHABLE_POINT", point.code(),
                        "%s point is not mutually reachable with the main path network".formatted(point.type())));
            }
        }
    }

    /** Iterative Tarjan's algorithm; returns the largest strongly connected component. */
    static Set<String> largestStronglyConnectedComponent(Map<String, List<String>> adjacency) {
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        Set<String> largest = Set.of();
        int counter = 0;

        for (String root : adjacency.keySet()) {
            if (index.containsKey(root)) {
                continue;
            }
            // Each frame: the node and the position of the next neighbour to visit.
            Deque<Object[]> callStack = new ArrayDeque<>();
            callStack.push(new Object[] {root, 0});
            index.put(root, counter);
            lowLink.put(root, counter);
            counter++;
            stack.push(root);
            onStack.add(root);

            while (!callStack.isEmpty()) {
                Object[] frame = callStack.peek();
                String node = (String) frame[0];
                int next = (Integer) frame[1];
                List<String> neighbours = adjacency.get(node);
                if (next < neighbours.size()) {
                    frame[1] = next + 1;
                    String neighbour = neighbours.get(next);
                    if (!index.containsKey(neighbour)) {
                        index.put(neighbour, counter);
                        lowLink.put(neighbour, counter);
                        counter++;
                        stack.push(neighbour);
                        onStack.add(neighbour);
                        callStack.push(new Object[] {neighbour, 0});
                    } else if (onStack.contains(neighbour)) {
                        lowLink.put(node, Math.min(lowLink.get(node), index.get(neighbour)));
                    }
                    continue;
                }
                callStack.pop();
                if (!callStack.isEmpty()) {
                    String parent = (String) callStack.peek()[0];
                    lowLink.put(parent, Math.min(lowLink.get(parent), lowLink.get(node)));
                }
                if (lowLink.get(node).equals(index.get(node))) {
                    Set<String> component = new HashSet<>();
                    String member;
                    do {
                        member = stack.pop();
                        onStack.remove(member);
                        component.add(member);
                    } while (!member.equals(node));
                    if (component.size() > largest.size()) {
                        largest = component;
                    }
                }
            }
        }
        return largest;
    }
}
