package org.rcs3000.simulator.app;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.rcs3000.common.api.ApiResponse;
import org.rcs3000.common.web.HttpClients;
import org.rcs3000.contracts.fleet.VehicleStatusDto;
import org.rcs3000.simulator.core.Pose;
import org.rcs3000.simulator.core.SimulatedAgv;
import org.rcs3000.simulator.core.SimulationEngine;
import org.rcs3000.simulator.core.SiteMap;
import org.rcs3000.simulator.core.VirtualVehicle;
import org.rcs3000.simulator.map.MapClient;
import org.rcs3000.simulator.map.SiteMaps;
import org.rcs3000.vda5050.client.AgvClient;
import org.rcs3000.vda5050.transport.MqttTransport;
import org.rcs3000.vda5050.transport.hivemq.HiveMqTransport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Creates the configured vehicles, connects them to the broker and runs the simulation. */
@Component
public class SimulatorRunner implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SimulatorRunner.class);

    private final SimulatorProperties properties;
    private final MapClient mapClient;
    private final RestClient fleet;
    private final SimulationEngine engine;
    private volatile SiteMap siteMap;
    private volatile boolean running;

    public SimulatorRunner(SimulatorProperties properties, RestClient.Builder restClientBuilder) {
        this.properties = properties;
        this.mapClient = new MapClient(restClientBuilder, properties.map().resourceUrl());
        this.fleet = HttpClients.withTimeouts(restClientBuilder.clone(), properties.fleetUrl()).build();
        this.engine = new SimulationEngine(properties.tickMillis(), properties.timeScale());
    }

    @Override
    public void start() {
        siteMap = loadMap().orElse(null);
        for (SimulatorProperties.VehicleConfig config : properties.vehicles()) {
            addVehicle(config);
        }
        if (properties.restoreVehicles()) {
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

    /**
     * Starts the simulated vehicles fleet control still knows, other than the configured ones, where
     * they were last seen: vehicles added at run time survive a restart of the simulator, like real
     * ones survive a reboot. Without fleet control nothing is restored.
     */
    private void restoreKnownVehicles() {
        List<VehicleStatusDto> known;
        try {
            ApiResponse<List<VehicleStatusDto>> response = fleet.get().uri("/api/fleet/v1/vehicles").retrieve()
                    .body(new ParameterizedTypeReference<>() { });
            known = response == null || response.data() == null ? List.of() : response.data();
        } catch (RuntimeException e) {
            log.warn("could not ask fleet control at {} for earlier vehicles: {}", properties.fleetUrl(), e.getMessage());
            return;
        }
        for (VehicleStatusDto v : known) {
            if (!properties.manufacturer().equals(v.manufacturer()) || engine.find(v.serialNumber()).isPresent()
                    || !v.positionInitialized() || siteMap == null || !siteMap.mapId().equals(v.mapCode())) {
                continue;
            }
            // on its last point if it stands there, else where it was
            String point = siteMap.point(v.lastNodeId())
                    .filter(p -> Math.hypot(p.x() - v.x(), p.y() - v.y()) <= properties.profile().nodeTolerance())
                    .map(p -> v.lastNodeId()).orElse(null);
            try {
                addVehicle(new SimulatorProperties.VehicleConfig(v.serialNumber(), point, v.x(), v.y(), v.theta(),
                        v.battery()));
                log.info("restored vehicle {} at {}", v.serialNumber(), point != null ? point : v.x() + ", " + v.y());
            } catch (RuntimeException e) {
                log.warn("could not restore vehicle {}: {}", v.serialNumber(), e.getMessage());
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
        String code = properties.map().code();
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(SiteMaps.from(mapClient.activeTopology(code)));
        } catch (RuntimeException e) {
            log.warn("could not load map {} from {}: {}", code, properties.map().resourceUrl(), e.getMessage());
            return Optional.empty();
        }
    }
}
