package org.fleetloom.simulator.scenario;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.fleetloom.contracts.dispatch.CreateOrderDto;
import org.fleetloom.contracts.dispatch.OrderStatus;
import org.fleetloom.contracts.dispatch.OrderType;
import org.fleetloom.contracts.dispatch.OrderViewDto;
import org.fleetloom.simulator.app.SimulatorProperties;
import org.fleetloom.simulator.app.SimulatorRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Runs one {@link Scenario} at a time (SIM-12): adds its vehicles, sends its orders on schedule and
 * follows them until all are finished, then reports throughput and timing. Around transport orders
 * it plays the warehouse: carriers arriving at docks, being loaded onto trucks, going back from
 * stations.
 */
public class ScenarioRunner implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);
    /** How long calls to a platform that does not answer are repeated. */
    static final Duration RETRY_FOR = Duration.ofMinutes(2);

    private final SimulatorRunner simulator;
    private final Platform platform;
    private final String manufacturer;
    private final String defaultMapCode;
    private final Clock clock;
    private final Duration pollInterval;
    private final Duration retryDelay;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("scenario").daemon().unstarted(r));
    private ScenarioRun current;
    private ScheduledFuture<?> poller;

    public ScenarioRunner(SimulatorRunner simulator, Platform platform, SimulatorProperties properties, Clock clock,
            Duration pollInterval) {
        this.simulator = simulator;
        this.platform = platform;
        this.manufacturer = properties.manufacturer();
        this.defaultMapCode = properties.map().code();
        this.clock = clock;
        this.pollInterval = pollInterval;
        this.retryDelay = pollInterval.multipliedBy(2);
    }

    /** Starts a run; refused while another one is running. */
    public synchronized ScenarioRun.Report start(Scenario scenario) {
        if (current != null && current.state == ScenarioRun.State.RUNNING) {
            throw new IllegalStateException("scenario %s is still running".formatted(current.scenario.name()));
        }
        if (poller != null) {
            poller.cancel(false); // still following the orders of the previous run
        }
        ScenarioRun run = new ScenarioRun(scenario, clock.instant());
        current = run;
        log.info("scenario {} starts: {} vehicle(s), {} order(s)", scenario.name(), scenario.vehicles().size(),
                scenario.plannedOrders());
        if (scenario.timeScale() != null) {
            run.previousTimeScale = simulator.engine().timeScale();
            simulator.engine().setTimeScale(scenario.timeScale());
        }
        for (Scenario.VehicleSpec v : scenario.vehicles()) {
            addVehicle(run, v);
        }
        String runId = UUID.randomUUID().toString().substring(0, 6);
        List<Scenario.OrderStream> streams = scenario.orders();
        for (int s = 0; s < streams.size(); s++) {
            Scenario.OrderStream stream = streams.get(s);
            for (int n = 0; n < stream.orderCount(); n++) {
                long delay = stream.startDelay().plus(stream.interval().multipliedBy(n)).toMillis();
                Ticket ticket = new Ticket(run, s, n, "%s-%s-%d-%d".formatted(name(scenario), runId, s, n), runId);
                executor.schedule(() -> send(ticket, null), delay, TimeUnit.MILLISECONDS);
            }
        }
        poller = executor.scheduleWithFixedDelay(() -> poll(run), pollInterval.toMillis(), pollInterval.toMillis(),
                TimeUnit.MILLISECONDS);
        executor.schedule(() -> finish(run, ScenarioRun.State.STOPPED, "ran longer than " + scenario.maxRunTime()),
                scenario.maxRunTime().toMillis(), TimeUnit.MILLISECONDS);
        return run.report(clock.instant());
    }

    public synchronized Optional<ScenarioRun.Report> current() {
        return Optional.ofNullable(current).map(r -> r.report(clock.instant()));
    }

    /** Stops sending orders; orders already sent go on. */
    public synchronized Optional<ScenarioRun.Report> stop() {
        if (current != null) {
            finish(current, ScenarioRun.State.STOPPED, "stopped by hand");
        }
        return current();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private void addVehicle(ScenarioRun run, Scenario.VehicleSpec v) {
        if (simulator.engine().find(v.serialNumber()).isPresent()) {
            return; // still there from an earlier run: it goes on from where it is
        }
        // registered first, so the vehicle drives at the speed of its type
        if (v.registerAs() != null) {
            try {
                platform.registerVehicle(v.serialNumber(), v.registerAs(), manufacturer, v.initialPoint());
            } catch (RuntimeException e) {
                run.error("could not register " + v.serialNumber() + ": " + e.getMessage());
            }
        }
        try {
            simulator.addVehicle(new SimulatorProperties.VehicleConfig(v.serialNumber(), v.initialPoint(), v.x(), v.y(),
                    0, v.battery(), v.maxSpeed()));
        } catch (IllegalArgumentException e) {
            run.error("vehicle " + v.serialNumber() + ": " + e.getMessage());
        }
    }

    /** The n-th order of stream s of a run. */
    private record Ticket(ScenarioRun run, int stream, int n, String externalNo, String runId) {

        Scenario.OrderStream spec() {
            return run.scenario.orders().get(stream);
        }
    }

    /** @param waitingSince when the order first had to wait, {@code null} on the first try */
    private void send(Ticket t, Instant waitingSince) {
        ScenarioRun run = t.run;
        synchronized (this) {
            if (run.state != ScenarioRun.State.RUNNING) {
                return;
            }
        }
        Scenario.OrderStream stream = t.spec();
        if (stream.type() == OrderType.MOVE) {
            create(run, new ScenarioRun.Open(t.stream, false, null), new CreateOrderDto(t.externalNo, OrderType.MOVE,
                    stream.priority(), null, null, null, stream.mapCode() == null ? defaultMapCode : stream.mapCode(),
                    pick(stream.targetPoints(), t.n), null, null, null), () -> { });
            return;
        }
        String carrier;
        try {
            carrier = freeTarget(stream.targetLocation(), stream.targetZone()) ? source(t) : null;
        } catch (HttpClientErrorException e) {
            synchronized (this) {
                run.notCreated(new ScenarioRun.Open(t.stream, false, null), t.externalNo + ": " + e.getMessage());
            }
            return;
        } catch (RuntimeException e) {
            // fleetloom-resource is unreachable or failing, e.g. restarting: like waiting for a dock
            log.warn("scenario {}: {} waits, the platform did not answer: {}", run.scenario.name(), t.externalNo,
                    e.getMessage());
            carrier = null;
        }
        if (carrier == null) {
            // no free dock, nothing to move or nowhere to put it yet: try again in a moment
            Instant now = clock.instant();
            synchronized (this) {
                if (waitingSince == null) {
                    run.streams.get(t.stream).waited++;
                } else if (Duration.between(waitingSince, now).compareTo(stream.maxWaitTime()) >= 0) {
                    run.notCreated(new ScenarioRun.Open(t.stream, false, null), "%s: gave up after waiting %s for %s"
                            .formatted(t.externalNo, stream.maxWaitTime(), waitingFor(stream)));
                    return;
                }
                run.pending++;
            }
            Instant since = waitingSince == null ? now : waitingSince;
            executor.schedule(() -> {
                synchronized (this) {
                    run.pending--;
                }
                send(t, since);
            }, retryDelay.toMillis(), TimeUnit.MILLISECONDS);
            return;
        }
        String arrived = stream.arrive() != null ? carrier : null;
        create(run, new ScenarioRun.Open(t.stream, false, carrier), new CreateOrderDto(t.externalNo, OrderType.TRANSPORT,
                stream.priority(), carrier, stream.targetLocation(), stream.targetZone(), null, null, null, null,
                stream.template()), () -> {
                    if (arrived != null) {
                        remove(run, arrived); // nobody comes for it: the truck takes it away again
                    }
                });
    }

    private static String waitingFor(Scenario.OrderStream s) {
        String target = "a free location in " + s.targetZone();
        if (s.arrive() != null) {
            return "a free dock in %s or %s".formatted(s.arrive().zone(), target);
        }
        if (s.fromZone() != null) {
            return "an idle carrier in %s%s or %s".formatted(s.fromZone(),
                    s.sku() == null ? "" : " holding " + s.sku(), target);
        }
        return target;
    }

    /**
     * The carrier to move: a given one, an idle one from {@code fromZone}, or a new one that has just
     * arrived. {@code null} when there is none (yet).
     */
    private String source(Ticket t) {
        Scenario.OrderStream stream = t.spec();
        if (stream.carriers() != null && !stream.carriers().isEmpty()) {
            return pick(stream.carriers(), t.n);
        }
        if (stream.fromZone() != null) {
            List<String> idle = platform.locations(stream.fromZone()).stream()
                    .filter(Platform.Location::idleCarrier).map(Platform.Location::carrierCode).toList();
            if (stream.sku() != null && !idle.isEmpty()) {
                List<String> holding = platform.carriersWithGoods(stream.sku());
                idle = idle.stream().filter(holding::contains).toList();
            }
            synchronized (this) {
                List<String> free = idle.stream().filter(c -> !t.run.claimed.contains(c)).toList();
                if (free.isEmpty()) {
                    return null;
                }
                String carrier = pick(free, t.n);
                t.run.claimed.add(carrier);
                return carrier;
            }
        }
        Scenario.Arrival arrive = stream.arrive();
        List<Platform.Location> docks = platform.locations(arrive.zone()).stream().filter(Platform.Location::empty).toList();
        if (docks.isEmpty()) {
            return null;
        }
        String code = "%s-%s-%d-%d".formatted(arrive.codePrefix(), t.runId, t.stream, t.n);
        List<Platform.GoodsLine> goods = arrive.goods().isEmpty() ? List.of()
                : List.of(new Platform.GoodsLine(pick(arrive.goods(), t.n), arrive.goodsQuantity()));
        platform.createCarrier(code, arrive.carrierType(), pick(docks, t.n).code(), goods);
        synchronized (this) {
            t.run.claimed.add(code);
        }
        return code;
    }

    /**
     * Whether the target location, or one in the target zone, is free to take a carrier. For a given
     * location or an unknown zone, dispatch has the say.
     */
    private boolean freeTarget(String location, String zone) {
        if (location != null) {
            return true;
        }
        List<Platform.Location> all = platform.locations(zone);
        return all.isEmpty() || all.stream().anyMatch(Platform.Location::empty);
    }

    /**
     * Hands an order to dispatch. When dispatch does not answer, e.g. while it restarts, the same order
     * (same external number, so it is not taken twice) is sent again for a while.
     *
     * @param onRefused runs when the order is not taken in the end
     */
    private void create(ScenarioRun run, ScenarioRun.Open open, CreateOrderDto order, Runnable onRefused) {
        retrying(run, order.externalNo(), () -> {
            var created = platform.createOrder(order);
            synchronized (this) {
                run.created(created, open);
            }
            if (created.status() == OrderStatus.REJECTED) {
                onRefused.run();
            }
        }, e -> {
            synchronized (this) {
                run.notCreated(open, order.externalNo() + ": " + e.getMessage());
            }
            onRefused.run();
        });
    }

    /**
     * Runs a call to the platform; when the platform does not answer or fails on its side, the call is
     * tried again for {@link #RETRY_FOR}. A refusal (4xx) or running out of time goes to {@code failed}.
     */
    private void retrying(ScenarioRun run, String what, Runnable call, Consumer<RuntimeException> failed) {
        retrying(run, what, call, failed, clock.instant());
    }

    private void retrying(ScenarioRun run, String what, Runnable call, Consumer<RuntimeException> failed, Instant since) {
        try {
            call.run();
        } catch (HttpClientErrorException e) {
            failed.accept(e);
        } catch (RuntimeException e) {
            if (Duration.between(since, clock.instant()).compareTo(RETRY_FOR) >= 0) {
                failed.accept(e);
                return;
            }
            log.warn("scenario {}: {} failed, trying again: {}", run.scenario.name(), what, e.getMessage());
            later(run, retryDelay, () -> retrying(run, what, call, failed, since));
        }
    }

    /** What happens to a carrier after its order completed: it leaves the site, or goes back after a while. */
    private void followUp(ScenarioRun run, ScenarioRun.Open done, OrderViewDto order) {
        Scenario.OrderStream stream = run.scenario.orders().get(done.stream());
        if (done.isReturn() || done.carrier() == null) {
            return;
        }
        if (stream.depart() != null) {
            later(run, stream.departDelay(), () -> retrying(run, "departure of " + done.carrier(), () -> {
                platform.removeCarrier(done.carrier());
                synchronized (this) {
                    run.streams.get(done.stream()).departed++;
                }
            }, e -> {
                synchronized (this) {
                    run.error("carrier %s could not depart: %s".formatted(done.carrier(), e.getMessage()));
                }
            }));
        } else if (stream.returnAfter() != null) {
            synchronized (this) {
                run.claimed.add(done.carrier()); // it is still at the station, not free for others
            }
            later(run, stream.returnDelay(), () -> sendBack(run, done, order, stream, 0));
        }
    }

    private void sendBack(ScenarioRun run, ScenarioRun.Open done, OrderViewDto order, Scenario.OrderStream stream,
            int attempt) {
        boolean free;
        try {
            free = freeTarget(null, stream.returnZone());
        } catch (RuntimeException e) {
            free = false;
        }
        if (!free) {
            synchronized (this) {
                if (attempt == 0) {
                    run.returns.computeIfAbsent(done.stream(),
                            i -> new ScenarioRun.Stats(run.streams.get(i).name + " (return)", 0)).waited++;
                }
            }
            later(run, retryDelay, () -> sendBack(run, done, order, stream, attempt + 1));
            return;
        }
        String externalNo = (order.externalNo() == null ? order.orderNo() : order.externalNo()) + "-R" + attempt;
        create(run, new ScenarioRun.Open(done.stream(), true, done.carrier()), new CreateOrderDto(externalNo,
                OrderType.TRANSPORT, stream.priority(), done.carrier(), null, stream.returnZone(), null, null, null,
                null, stream.template()), () -> { });
    }

    /** Runs a follow-up later; the run is not done before it ran. */
    private void later(ScenarioRun run, Duration delay, Runnable task) {
        synchronized (this) {
            run.pending++;
        }
        executor.schedule(() -> {
            try {
                task.run();
            } finally {
                synchronized (this) {
                    run.pending--;
                }
            }
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void remove(ScenarioRun run, String carrier) {
        retrying(run, "removal of " + carrier, () -> platform.removeCarrier(carrier), e -> {
            synchronized (this) {
                run.error("could not remove carrier %s: %s".formatted(carrier, e.getMessage()));
            }
        });
    }

    private void poll(ScenarioRun run) {
        List<String> open;
        synchronized (this) {
            open = List.copyOf(run.open.keySet());
        }
        for (String orderNo : open) {
            try {
                var order = platform.order(orderNo);
                ScenarioRun.Open done;
                synchronized (this) {
                    done = run.observed(order);
                    if (done != null) {
                        run.release(done);
                    }
                }
                if (done != null && order.status() == OrderStatus.COMPLETED) {
                    followUp(run, done, order);
                }
            } catch (RuntimeException e) {
                log.warn("scenario {}: could not read order {}: {}", run.scenario.name(), orderNo, e.getMessage());
            }
        }
        synchronized (this) {
            if (run.allDone()) {
                finish(run, ScenarioRun.State.FINISHED, null);
            }
        }
    }

    private synchronized void finish(ScenarioRun run, ScenarioRun.State state, String why) {
        if (run.state != ScenarioRun.State.RUNNING) {
            return;
        }
        run.end(state, clock.instant());
        if (run.previousTimeScale != null) {
            simulator.engine().setTimeScale(run.previousTimeScale); // the speed is the run's only while it runs
        }
        if (run == current && poller != null && state == ScenarioRun.State.FINISHED) {
            poller.cancel(false);
        }
        log.info("scenario {} {}{}: {}", run.scenario.name(), state.name().toLowerCase(),
                why == null ? "" : " (" + why + ")", run.report(clock.instant()));
    }

    private static <T> T pick(List<T> values, int n) {
        return values.get(n % values.size());
    }

    private static String name(Scenario s) {
        return s.name() == null || s.name().isBlank() ? "scenario" : s.name().replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
