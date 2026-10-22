package org.rcs3000.simulator.scenario;

import java.time.Clock;
import java.time.Duration;

import org.rcs3000.simulator.app.SimulatorProperties;
import org.rcs3000.simulator.app.SimulatorRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class ScenarioConfiguration {

    @Bean(destroyMethod = "close")
    ScenarioRunner scenarioRunner(SimulatorRunner simulator, SimulatorProperties properties, RestClient.Builder rest) {
        Platform platform = new HttpPlatform(rest, properties.dispatchUrl(), properties.map().resourceUrl());
        return new ScenarioRunner(simulator, platform, properties, Clock.systemUTC(), Duration.ofSeconds(2));
    }
}
