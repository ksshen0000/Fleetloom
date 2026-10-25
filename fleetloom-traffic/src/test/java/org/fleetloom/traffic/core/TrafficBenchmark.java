package org.fleetloom.traffic.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.fleetloom.common.error.BizException;
import org.fleetloom.contracts.map.MapTopologyDto;
import org.fleetloom.contracts.traffic.RouteStatus;
import org.fleetloom.contracts.traffic.RouteViewDto;

/**
 * Throughput of traffic control under random targets, for comparing planning strategies. Not part of
 * the normal build: run with {@code ./mvnw -pl fleetloom-traffic test -Dtest=TrafficBenchmark
 * -Dfleetloom.benchmark=true}. Prints arrivals per scenario and seed; every run also checks that the
 * vehicles kept their distance.
 */
class TrafficBenchmark {

    private static final int SECONDS = 600;
    private static final long[] SEEDS = {1, 2, 3, 4, 5, 6, 7, 8};

    @Test
    void compareSettings() {
        assumeTrue(Boolean.getBoolean("fleetloom.benchmark"));
        TrafficSettings d = TrafficSettings.defaults();
        Map<String, TrafficSettings> variants = new java.util.LinkedHashMap<>();
        variants.put("reservations only", withFlow(d, 0, 0));
        variants.put("default", d);
        variants.forEach((name, settings) -> {
            System.out.printf("%n== %s%n", name);
            run("grid 4x4, 4 vehicles", () -> TestMaps.grid(4, 4, 4), 4, 4, settings);
            run("grid 4x4, 6 vehicles", () -> TestMaps.grid(4, 4, 4), 4, 6, settings);
            run("grid 5x5, 8 vehicles", () -> TestMaps.grid(5, 5, 4), 5, 8, settings);
            run("grid 6x6, 12 vehicles", () -> TestMaps.grid(6, 6, 4), 6, 12, settings);
        });
    }

    /** Prints, every 30 s, the arrivals so far and the routes that wait, for one scenario and seed. */
    @Test
    void trace() {
        assumeTrue(System.getProperty("fleetloom.benchmark.trace") != null);
        String[] arg = System.getProperty("fleetloom.benchmark.trace").split(",");
        int size = Integer.parseInt(arg[0]);
        int count = Integer.parseInt(arg[1]);
        long seed = Long.parseLong(arg[2]);
        TrafficSettings settings = withFlow(TrafficSettings.defaults(), Double.parseDouble(arg[3]), Double.parseDouble(arg[4]));
        TrafficHarness h = new TrafficHarness(TestMaps.grid(size, size, 4), settings);
        List<String> vehicles = new ArrayList<>();
        List<String> starts = spread(size, count);
        for (int i = 0; i < count; i++) {
            vehicles.add("V" + (i + 1));
            h.vehicle("V" + (i + 1), starts.get(i));
        }
        tracing = h;
        randomTraffic(h, vehicles, size, seed);
        tracing = null;
    }

    private static TrafficHarness tracing;

    private static void printState(TrafficHarness h, int second) {
        long arrivals = h.progress.stream().filter(p -> p.status() == RouteStatus.ARRIVED).count();
        System.out.printf("t=%3d arrivals %d deadlocks %d%n", second, arrivals, h.deadlocks.size());
        for (RouteViewDto r : h.traffic().routes()) {
            if (r.status().isActive()) {
                System.out.printf("   %s %s -> %s passed %d released %d blockedBy %s on %s  %s%n", r.vehicleCode(), r.status(),
                        r.targetPoint(), r.passedIndex(), r.releasedIndex(), r.blockedBy(), r.blockedResource(), r.nodes());
            }
        }
    }

    static TrafficSettings withFlow(TrafficSettings s, double counterflow, double follow) {
        return new TrafficSettings(s.lookaheadNodes(), s.lookaheadDistance(), s.turnPenaltyPerRadian(), s.defaultSpeed(),
                s.occupiedPenalty(), s.nodeTolerance(), s.blockedEventAfter(), s.replanAfter(), counterflow, follow);
    }

    private static void run(String scenario, Supplier<MapTopologyDto> map, int size, int count, TrafficSettings settings) {
        long total = 0;
        long deadlocks = 0;
        StringBuilder perSeed = new StringBuilder();
        for (long seed : SEEDS) {
            TrafficHarness h = new TrafficHarness(map.get(), settings);
            List<String> vehicles = new ArrayList<>();
            List<String> starts = spread(size, count);
            for (int i = 0; i < count; i++) {
                vehicles.add("V" + (i + 1));
                h.vehicle("V" + (i + 1), starts.get(i));
            }
            int arrivals = randomTraffic(h, vehicles, size, seed);
            assertThat(h.minDistance()).as("%s seed %d", scenario, seed).isGreaterThan(2.0);
            total += arrivals;
            deadlocks += h.deadlocks.size();
            perSeed.append(' ').append(arrivals);
        }
        System.out.printf("%-24s arrivals %5d (mean %.1f)  deadlock events %3d  per seed:%s%n", scenario, total,
                (double) total / SEEDS.length, deadlocks, perSeed);
    }

    /** Start points spread over the grid: corners first, then the inner points. */
    private static List<String> spread(int size, int count) {
        List<String> points = new ArrayList<>(List.of(TestMaps.name(0, 0), TestMaps.name(0, size - 1),
                TestMaps.name(size - 1, 0), TestMaps.name(size - 1, size - 1)));
        for (int r = 1; r < size - 1 && points.size() < count; r++) {
            for (int c = 1; c < size - 1 && points.size() < count; c++) {
                points.add(TestMaps.name(r, c));
            }
        }
        return points.subList(0, count);
    }

    private static int randomTraffic(TrafficHarness h, List<String> vehicles, int size, long seed) {
        h.run(0.2);
        Random random = new Random(seed);
        Map<String, String> targets = new HashMap<>();
        for (int second = 0; second < SECONDS; second++) {
            for (String v : vehicles) {
                boolean idle = h.traffic().route(v).map(r -> !r.status().isActive()).orElse(true);
                if (!idle) {
                    continue;
                }
                Set<String> taken = new HashSet<>(targets.values());
                vehicles.forEach(o -> taken.add(h.vehicle(o).lastNodeId()));
                List<String> free = new ArrayList<>();
                for (int r = 0; r < size; r++) {
                    for (int c = 0; c < size; c++) {
                        if (!taken.contains(TestMaps.name(r, c))) {
                            free.add(TestMaps.name(r, c));
                        }
                    }
                }
                String target = free.get(random.nextInt(free.size()));
                try {
                    RouteViewDto route = h.traffic().requestRoute(v, target, null);
                    targets.put(v, route.targetPoint());
                } catch (BizException e) {
                    // e.g. still rolling after a cancel; next second
                }
            }
            h.run(1);
            if (h == tracing && second % 30 == 29) {
                printState(h, second + 1);
            }
        }
        return (int) h.progress.stream().filter(p -> p.status() == RouteStatus.ARRIVED).count();
    }
}
