package org.fleetloom.simulator.app;

import java.nio.file.Path;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class SimulatorConfiguration {

    @Bean
    @ConditionalOnExpression("!'${fleetloom.simulator.map.file:}'.isBlank()")
    MapSource fileMapSource(SimulatorProperties properties) {
        return new FileMapSource(Path.of(properties.map().file()), properties.map().code());
    }
}
