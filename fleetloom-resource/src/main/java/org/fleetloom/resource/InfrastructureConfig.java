package org.fleetloom.resource;

import java.time.Clock;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.fleetloom.common.event.valkey.ValkeyEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.json.JsonMapper;

/**
 * Database, event bus and scheduling wiring. Kept out of {@link ResourceApplication} so that web slice tests start
 * without a database or Valkey.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "org.fleetloom.resource", annotationClass = Mapper.class)
@EnableScheduling
class InfrastructureConfig {

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json, Clock clock) {
        return new ValkeyEventBus(connectionFactory, json, "fleetloom-resource", clock);
    }
}
