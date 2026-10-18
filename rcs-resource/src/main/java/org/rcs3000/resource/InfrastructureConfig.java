package org.rcs3000.resource;

import java.time.Clock;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Database and event bus wiring. Kept out of {@link ResourceApplication} so that web slice tests start
 * without a database or Valkey.
 */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "org.rcs3000.resource", annotationClass = Mapper.class)
class InfrastructureConfig {

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json, Clock clock) {
        return new ValkeyEventBus(connectionFactory, json, "rcs-resource", clock);
    }
}
