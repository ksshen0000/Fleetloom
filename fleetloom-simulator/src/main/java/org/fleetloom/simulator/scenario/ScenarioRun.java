package org.fleetloom.simulator.scenario;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderViewDto;

/** Bookkeeping of one scenario run; not thread-safe, the runner serialises access. */
public final class ScenarioRun {

    static final int MAX_ERRORS = 20;

    final Scenario scenario;
    final Instant startedAt;
    /** one per order stream, in the order of the scenario, then the return trips of streams that have them */
    final List<Stats> streams = new ArrayList<>();
    final Map<Integer, Stats> returns = new LinkedHashMap<>();
    /** order number → the stream it belongs to */
    final Map<String, Open> open = new LinkedHashMap<>();
    /** carriers this run is about to move, so that no second order picks them */
    final Set<String> claimed = new HashSet<>();
    final List<String> errors = new ArrayList<>();
    /** retries and follow-ups (departures, return trips) scheduled but not done yet */
    int pending;
    /** simulation speed before the run changed it */
    Double previousTimeScale;
    State state = State.RUNNING;
    Instant finishedAt;

    enum State { RUNNING, FINISHED, STOPPED }

    record Open(int stream, boolean isReturn, String carrier) {
    }

    ScenarioRun(Scenario scenario, Instant startedAt) {
        this.scenario = scenario;
        this.startedAt = startedAt;
        for (int i = 0; i < scenario.orders().size(); i++) {
            Scenario.OrderStream s = scenario.orders().get(i);
            streams.add(new Stats(s.label(i), s.orderCount()));
        }
    }

    Stats stats(Open o) {
        return o.isReturn ? returns.computeIfAbsent(o.stream,
                i -> new Stats(streams.get(i).name + " (return)", 0)) : streams.get(o.stream);
    }

    void created(OrderViewDto order, Open o) {
        Stats stats = stats(o);
        stats.sent++;
        if (o.isReturn) {
            stats.planned++;
        }
        if (order.status() == OrderStatus.REJECTED) {
            stats.rejected++;
            release(o);
            error("order rejected: " + order.reason());
        } else {
            open.put(order.orderNo(), o);
            stats.open++;
        }
    }

    void notCreated(Open o, String reason) {
        Stats stats = stats(o);
        stats.sent++;
        stats.rejected++;
        if (o.isReturn) {
            stats.planned++;
        }
        release(o);
        error(reason);
    }

    /** @return the bookkeeping entry of the order if it just ended, so the caller can act on how */
    Open observed(OrderViewDto order) {
        if (!order.status().isFinal()) {
            return null;
        }
        Open o = open.remove(order.orderNo());
        if (o == null) {
            return null;
        }
        Stats stats = stats(o);
        stats.open--;
        switch (order.status()) {
            case COMPLETED -> {
                stats.completed++;
                if (order.finishedAt() != null && order.createdAt() != null) {
                    stats.seconds.add(Duration.between(order.createdAt(), order.finishedAt()).toMillis() / 1000.0);
                }
            }
            case CANCELLED -> stats.cancelled++;
            default -> stats.failed++;
        }
        return o;
    }

    void release(Open o) {
        if (o.carrier != null) {
            claimed.remove(o.carrier);
        }
    }

    void error(String message) {
        if (errors.size() < MAX_ERRORS) {
            errors.add(message);
        }
    }

    boolean allDone() {
        return streams.stream().allMatch(s -> s.sent >= s.planned) && open.isEmpty() && pending == 0;
    }

    void end(State end, Instant at) {
        if (state == State.RUNNING) {
            state = end;
            finishedAt = at;
        }
    }

    private List<Stats> allStats() {
        List<Stats> all = new ArrayList<>(streams);
        all.addAll(returns.values());
        return all;
    }

    Report report(Instant now) {
        List<Stats> all = allStats();
        List<Double> seconds = all.stream().flatMap(s -> s.seconds.stream()).toList();
        Timing t = Timing.of(seconds);
        Instant end = finishedAt == null ? now : finishedAt;
        double hours = Math.max(Duration.between(startedAt, end).toMillis(), 1) / 3_600_000.0;
        int completed = all.stream().mapToInt(s -> s.completed).sum();
        return new Report(scenario.name(), state.name(), startedAt, finishedAt,
                all.stream().mapToInt(s -> s.planned).sum(), all.stream().mapToInt(s -> s.sent).sum(),
                all.stream().mapToInt(s -> s.rejected).sum(), completed, all.stream().mapToInt(s -> s.failed).sum(),
                all.stream().mapToInt(s -> s.cancelled).sum(), open.size(), t.avg, t.p95, t.max,
                round(completed / hours), all.stream().map(Stats::report).toList(), List.copyOf(errors));
    }

    private static Double round(Double v) {
        return v == null ? null : Math.round(v * 10) / 10.0;
    }

    /** Counts of one stream of orders. */
    static final class Stats {

        final String name;
        int planned;
        int sent;
        int rejected;
        int completed;
        int failed;
        int cancelled;
        int open;
        /** orders that had to wait for a free dock, location or carrier before they could be sent */
        int waited;
        /** carriers that were taken away after delivery */
        int departed;
        final List<Double> seconds = new ArrayList<>();

        Stats(String name, int planned) {
            this.name = name;
            this.planned = planned;
        }

        StreamReport report() {
            Timing t = Timing.of(seconds);
            return new StreamReport(name, planned, sent, rejected, completed, failed, cancelled, open, waited, departed,
                    t.avg, t.p95);
        }
    }

    private record Timing(Double avg, Double p95, Double max) {

        static Timing of(List<Double> seconds) {
            if (seconds.isEmpty()) {
                return new Timing(null, null, null);
            }
            List<Double> sorted = seconds.stream().sorted().toList();
            return new Timing(round(sorted.stream().mapToDouble(Double::doubleValue).average().orElse(0)),
                    round(sorted.get((int) Math.ceil(0.95 * sorted.size()) - 1)), round(sorted.getLast()));
        }
    }

    /**
     * @param planned   orders the scenario plans, plus the return trips sent so far
     * @param sent      orders handed to dispatch so far (rejected ones included)
     * @param open      orders not finished yet
     * @param perHour   completed orders per hour of the run so far
     * @param streams   the same counts per order stream
     * @param errors    the first few problems: rejections, unreachable services
     */
    public record Report(String name, String state, Instant startedAt, Instant finishedAt, int planned, int sent,
            int rejected, int completed, int failed, int cancelled, int open, Double avgSeconds, Double p95Seconds,
            Double maxSeconds, Double perHour, List<StreamReport> streams, List<String> errors) {
    }

    /**
     * @param waited   orders that waited for a free dock, location or carrier before they were sent
     * @param departed carriers taken away after delivery
     */
    public record StreamReport(String name, int planned, int sent, int rejected, int completed, int failed,
            int cancelled, int open, int waited, int departed, Double avgSeconds, Double p95Seconds) {
    }
}
