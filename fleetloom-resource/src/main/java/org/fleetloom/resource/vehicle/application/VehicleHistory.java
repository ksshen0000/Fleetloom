package org.fleetloom.resource.vehicle.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.fleet.VehicleStateUpdatedEvent;
import org.fleetloom.contracts.fleet.VehicleStatusDto;
import org.fleetloom.resource.vehicle.domain.HistorySampler;
import org.fleetloom.resource.vehicle.domain.VehicleHistoryRepository;
import org.fleetloom.resource.vehicle.domain.VehicleSample;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Vehicle status history (VEH-09): position and battery sampled from the live vehicle states and
 * kept for {@code fleetloom.vehicle-history.retention} (default 7 days).
 */
@Service
public class VehicleHistory implements ApplicationRunner {

    static final String GROUP = "fleetloom-resource-history";
    static final int MAX_LIMIT = 20_000;
    private static final Logger log = LoggerFactory.getLogger(VehicleHistory.class);

    private final VehicleHistoryRepository samples;
    private final EventBus events;
    private final Clock clock;
    private final Duration retention;
    private final HistorySampler sampler;

    public VehicleHistory(VehicleHistoryRepository samples, EventBus events, Clock clock,
            @Value("${fleetloom.vehicle-history.retention:7d}") Duration retention,
            @Value("${fleetloom.vehicle-history.min-distance:0.5}") double minDistance,
            @Value("${fleetloom.vehicle-history.max-interval:60s}") Duration maxInterval) {
        this.samples = samples;
        this.events = events;
        this.clock = clock;
        this.retention = retention;
        this.sampler = new HistorySampler(minDistance, Duration.ofSeconds(1), maxInterval);
    }

    @Override
    public void run(ApplicationArguments args) {
        // live: states that piled up while this service was down are not worth replaying
        events.subscribeLive(VehicleStateUpdatedEvent.TYPE, GROUP, VehicleStateUpdatedEvent.class,
                e -> record(e.payload().status()));
    }

    public synchronized void record(VehicleStatusDto s) {
        VehicleSample sample = new VehicleSample(s.code(), s.lastSeen() == null ? clock.instant() : s.lastSeen(),
                s.mapCode(), s.x(), s.y(), s.theta(), s.battery(), s.charging(), s.driving(), s.lastNodeId(),
                s.connection() == null ? "UNKNOWN" : s.connection().name());
        if (sampler.accept(sample)) {
            samples.insert(sample);
        }
    }

    /** Defaults to the last hour. */
    public List<VehicleSample> find(String vehicleCode, Instant from, Instant to, int limit) {
        Instant end = to == null ? clock.instant() : to;
        Instant start = from == null ? end.minus(Duration.ofHours(1)) : from;
        return samples.find(vehicleCode, start, end, Math.clamp(limit, 1, MAX_LIMIT));
    }

    @Scheduled(cron = "${fleetloom.vehicle-history.purge-cron:0 40 3 * * *}")
    public void purge() {
        int removed = samples.deleteBefore(clock.instant().minus(retention));
        if (removed > 0) {
            log.info("removed {} vehicle history samples older than {}", removed, retention);
        }
    }
}
