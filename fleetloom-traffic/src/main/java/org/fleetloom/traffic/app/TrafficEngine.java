package org.fleetloom.traffic.app;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.fleet.VehicleConnectionChangedEvent;
import org.fleetloom.contracts.fleet.VehicleForgottenEvent;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.map.MapPublishedEvent;
import org.fleetloom.traffic.core.TrafficController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the {@link TrafficController} on a single thread: HTTP requests, events and the periodic tick
 * are all queued onto it (docs/tech-evaluation.md section 4.4).
 */
public final class TrafficEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TrafficEngine.class);
    static final String CONSUMER_GROUP = "fleetloom-traffic";

    private final TrafficController controller;
    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("traffic").unstarted(r));

    public TrafficEngine(TrafficController controller) {
        this.controller = controller;
    }

    public void start(EventBus events, long tickMillis) {
        events.subscribe(VehicleStateUpdatedEvent.TYPE, CONSUMER_GROUP, VehicleStateUpdatedEvent.class,
                e -> submit(c -> c.onVehicleStatus(e.payload().status())));
        events.subscribe(VehicleConnectionChangedEvent.TYPE, CONSUMER_GROUP, VehicleConnectionChangedEvent.class,
                e -> submit(c -> c.onConnectionChanged(e.payload())));
        events.subscribe(VehicleForgottenEvent.TYPE, CONSUMER_GROUP, VehicleForgottenEvent.class,
                e -> submit(c -> c.onVehicleForgotten(e.payload().vehicleCode())));
        events.subscribe(MapPublishedEvent.TYPE, CONSUMER_GROUP, MapPublishedEvent.class,
                e -> submit(c -> c.onMapPublished(e.payload().mapCode())));
        thread.scheduleWithFixedDelay(() -> run(TrafficController::tick), tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    /** Runs {@code action} on the traffic thread and waits for its result; exceptions are rethrown. */
    public <T> T call(Function<TrafficController, T> action) {
        try {
            return thread.submit(() -> action.apply(controller)).get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("traffic controller did not answer in time", e);
        }
    }

    public void submit(Consumer<TrafficController> action) {
        thread.execute(() -> run(action));
    }

    private void run(Consumer<TrafficController> action) {
        try {
            action.accept(controller);
        } catch (RuntimeException e) {
            log.error("traffic controller failed", e);
        }
    }

    @Override
    public void close() {
        thread.shutdown();
    }
}
