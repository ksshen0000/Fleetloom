package org.fleetloom.dispatch.app;

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
import org.fleetloom.contracts.traffic.DeadlockDetectedEvent;
import org.fleetloom.contracts.traffic.RouteProgressedEvent;
import org.fleetloom.contracts.traffic.TrafficBlockedEvent;
import org.fleetloom.dispatch.core.Dispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs the {@link Dispatcher} on a single thread, fed by REST calls, events and a periodic tick. */
public final class DispatchEngine implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DispatchEngine.class);
    static final String CONSUMER_GROUP = "fleetloom-dispatch";

    private final Dispatcher dispatcher;
    private final ScheduledExecutorService thread =
            Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().name("dispatch").unstarted(r));

    public DispatchEngine(Dispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    public void start(EventBus events, long tickMillis) {
        call(d -> {
            d.start();
            return null;
        });
        events.subscribe(VehicleStateUpdatedEvent.TYPE, CONSUMER_GROUP, VehicleStateUpdatedEvent.class,
                e -> submit(d -> d.onVehicleStatus(e.payload().status())));
        events.subscribe(VehicleConnectionChangedEvent.TYPE, CONSUMER_GROUP, VehicleConnectionChangedEvent.class,
                e -> submit(d -> d.onConnectionChanged(e.payload())));
        events.subscribe(VehicleForgottenEvent.TYPE, CONSUMER_GROUP, VehicleForgottenEvent.class,
                e -> submit(d -> d.onVehicleForgotten(e.payload().vehicleCode())));
        events.subscribe(TrafficBlockedEvent.TYPE, CONSUMER_GROUP, TrafficBlockedEvent.class,
                e -> submit(d -> d.onTrafficBlocked(e.payload())));
        events.subscribe(RouteProgressedEvent.TYPE, CONSUMER_GROUP, RouteProgressedEvent.class,
                e -> submit(d -> d.onRouteProgressed(e.payload())));
        events.subscribe(DeadlockDetectedEvent.TYPE, CONSUMER_GROUP, DeadlockDetectedEvent.class,
                e -> submit(d -> d.onDeadlock(e.payload().vehicleCodes())));
        thread.scheduleWithFixedDelay(() -> run(Dispatcher::tick), tickMillis, tickMillis, TimeUnit.MILLISECONDS);
    }

    public <T> T call(Function<Dispatcher, T> action) {
        try {
            return thread.submit(() -> action.apply(dispatcher)).get(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("dispatcher did not answer in time", e);
        }
    }

    private void submit(Consumer<Dispatcher> action) {
        thread.execute(() -> run(action));
    }

    private void run(Consumer<Dispatcher> action) {
        try {
            action.accept(dispatcher);
        } catch (RuntimeException e) {
            log.error("dispatcher failed", e);
        }
    }

    @Override
    public void close() {
        thread.shutdown();
    }
}
