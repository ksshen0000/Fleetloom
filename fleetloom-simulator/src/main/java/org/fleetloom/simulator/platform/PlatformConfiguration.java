package org.fleetloom.simulator.platform;

import java.time.Clock;
import java.time.Duration;

import org.fleetloom.common.web.HttpClients;
import org.fleetloom.simulator.app.KnownVehicles;
import org.fleetloom.simulator.app.MapSource;
import org.fleetloom.simulator.app.SimulatorProperties;
import org.fleetloom.simulator.app.SimulatorRunner;
import org.fleetloom.simulator.app.VehicleSpeeds;
import org.fleetloom.simulator.scenario.HttpPlatform;
import org.fleetloom.simulator.scenario.ScenarioRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Everything that ties the simulator to the Fleetloom services; see {@link PlatformProperties}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "fleetloom.simulator.platform", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlatformConfiguration {

    /** The map of the map service, unless a map file is configured. */
    @Bean
    @ConditionalOnExpression("!'${fleetloom.simulator.map.code:}'.isBlank() && '${fleetloom.simulator.map.file:}'.isBlank()")
    MapSource platformMapSource(SimulatorProperties simulator, PlatformProperties platform, RestClient.Builder rest) {
        return new PlatformMapSource(new MapClient(rest.clone(), platform.resourceUrl()), simulator.map().code(), platform.resourceUrl());
    }

    @Bean
    @ConditionalOnProperty(prefix = "fleetloom.simulator.platform", name = "restore-vehicles", havingValue = "true", matchIfMissing = true)
    KnownVehicles fleetVehicles(SimulatorProperties simulator, PlatformProperties platform, RestClient.Builder rest) {
        return new FleetVehicles(HttpClients.withTimeouts(rest.clone(), platform.fleetUrl()).build(),
                simulator.profile().nodeTolerance());
    }

    @Bean
    VehicleSpeeds registrySpeeds(PlatformProperties platform, RestClient.Builder rest) {
        return new RegistrySpeeds(HttpClients.withTimeouts(rest.clone(), platform.resourceUrl()).build());
    }

    @Bean(destroyMethod = "close")
    ScenarioRunner scenarioRunner(SimulatorRunner simulator, SimulatorProperties properties, PlatformProperties platform,
            RestClient.Builder rest) {
        return new ScenarioRunner(simulator, new HttpPlatform(rest, platform.dispatchUrl(), platform.resourceUrl()), properties,
                Clock.systemUTC(), Duration.ofSeconds(2));
    }
}
