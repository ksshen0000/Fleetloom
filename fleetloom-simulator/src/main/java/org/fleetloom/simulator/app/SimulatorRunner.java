package org.fleetloom.simulator.app;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.fleetloom.simulator.core.Pose;
import org.fleetloom.simulator.core.SimulatedAgv;
import org.fleetloom.simulator.core.SimulationEngine;
import org.fleetloom.simulator.core.SiteMap;
import org.fleetloom.simulator.core.VirtualVehicle;
import org.fleetloom.vda5050.client.AgvClient;
import org.fleetloom.vda5050.transport.MqttTransport;
import org.fleetloom.vda5050.transport.hivemq.HiveMqTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/** Creates the configured vehicles, connects them to the broker and runs the simulation. */
@Component
public class SimulatorRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final SimulatorProperties properties;
    private final MapSource mapSource;
    private final KnownVehicles knownVehicles;
    private final SimulationEngine engine;
    private volatile SiteMap siteMap;
    private volatile boolean running;

    /**
     * @param mapSource     where the map comes from; none: vehicles run without one
     * @param knownVehicles vehicles of an earlier run to start again; none: only the configured ones
     */
    public SimulatorRunner(SimulatorProperties properties, ObjectProvider<MapSource> mapSource,
            ObjectProvider<KnownVehicles> knownVehicles) {
        this.properties = properties;
        this.mapSource = mapSource.getIfAvailable();
        this.knownVehicles = knownVehicles.getIfAvailable();
        this.engine = new SimulationEngine(properties.tickMillis(), properties.timeScale());
    }

    @Override
    public void start() {
        siteMap = loadMap().orElse(null);
        for (SimulatorProperties.VehicleConfig config : properties.vehicles()) {
            addVehicle(config);
        }
        if (knownVehicles != null) {
            restoreKnownVehicles();
        }
        engine.start();
        running = true;
        log.info("simulating {} vehicle(s), map {}", engine.query(e -> e.agvs().size()),
                siteMap == null ? "none" : siteMap.mapId() + " v" + siteMap.version());
    }

    @Override
    public void stop() {
        engine.close();
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    public SimulationEngine engine() {
        return engine;
    }

    /** Reloads the active map and hands it to every vehicle. */
    public SiteMap reloadMap() {
        SiteMap loaded = loadMap().orElseThrow(() -> new IllegalStateException("no map configured"));
        siteMap = loaded;
        engine.execute(e -> e.agvs().forEach(a -> a.vehicle().setMap(loaded)));
        return loaded;
    }

    /** Adds a vehicle and connects it; it reports itself to fleet control right away (SIM-01). */
    public synchronized SimulatedAgv addVehicle(SimulatorProperties.VehicleConfig config) {
        if (engine.find(config.serialNumber()).isPresent()) {
            throw new IllegalArgumentException("vehicle " + config.serialNumber() + " already exists");
        }
        Pose pose = initialPose(config);
        VirtualVehicle vehicle = new VirtualVehicle(config.serialNumber(), properties.profile().toVehicleProfile(),
                siteMap, pose, config.initialPoint());
        if (config.battery() != null) {
            vehicle.setBattery(config.battery());
        }
        MqttTransport transport = new HiveMqTransport(properties.mqtt().host(), properties.mqtt().port(),
                properties.manufacturer() + "-" + config.serialNumber());
        AgvClient client = new AgvClient(transport, properties.mqtt().interfaceName(), properties.manufacturer(),
                config.serialNumber(), Clock.systemUTC());
        SimulatedAgv agv = new SimulatedAgv(vehicle, client, properties.stateInterval(), properties.visualizationInterval());
        agv.connect();
        engine.add(agv);
        return agv;
    }

    /** Disconnects the vehicle cleanly (it announces OFFLINE) and drops it. */
    public synchronized boolean removeVehicle(String serialNumber) {
        return engine.query(e -> e.remove(serialNumber));
    }

    public Optional<SiteMap> siteMap() {
        return Optional.ofNullable(siteMap);
    }

    /** Starts the vehicles of an earlier run that are not configured, see {@link KnownVehicles}. */
    private void restoreKnownVehicles() {
        List<SimulatorProperties.VehicleConfig> known;
        try {
            known = knownVehicles.find(properties.manufacturer(), Optional.ofNullable(siteMap));
        } catch (RuntimeException e) {
            log.warn("could not look up the vehicles of an earlier run: {}", e.getMessage());
            return;
        }
        for (SimulatorProperties.VehicleConfig config : known) {
            if (engine.find(config.serialNumber()).isPresent()) {
                continue;
            }
            try {
                addVehicle(config);
                log.info("restored vehicle {} at {}", config.serialNumber(),
                        config.initialPoint() != null ? config.initialPoint() : config.x() + ", " + config.y());
            } catch (RuntimeException e) {
                log.warn("could not restore vehicle {}: {}", config.serialNumber(), e.getMessage());
            }
        }
    }

    private Pose initialPose(SimulatorProperties.VehicleConfig config) {
        if (config.initialPoint() != null && siteMap != null) {
            return siteMap.point(config.initialPoint())
                    .map(p -> new Pose(p.x(), p.y(), config.theta()))
                    .orElseThrow(() -> new IllegalArgumentException("point %s is not on map %s"
                            .formatted(config.initialPoint(), siteMap.mapId())));
        }
        if (config.x() != null && config.y() != null) {
            return new Pose(config.x(), config.y(), config.theta());
        }
        return null;
    }

    private Optional<SiteMap> loadMap() {
        if (mapSource == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapSource.load());
        } catch (RuntimeException e) {
            log.warn("could not load the map from {}: {}", mapSource.describe(), e.getMessage());
            return Optional.empty();
        }
    }
}
