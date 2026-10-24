package org.fleetloom.gateway.push;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.util.Map;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.common.event.valkey.ValkeyEventBus;
import org.fleetloom.gateway.GatewayProperties;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class PushConfiguration {

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json) {
        return new ValkeyEventBus(connectionFactory, json, "fleetloom-gateway", Clock.systemUTC());
    }

    @Bean
    PushHub pushHub(JsonMapper json) {
        return new PushHub(json);
    }

    /** Ahead of the gateway routes, which would otherwise answer 404 for {@code /ws}. */
    @Bean
    HandlerMapping pushHandlerMapping(PushHub hub) {
        return new SimpleUrlHandlerMapping(Map.of("/ws", hub), Ordered.HIGHEST_PRECEDENCE);
    }

    /**
     * Each gateway instance needs every event, so each gets its own consumer group; live subscriptions
     * skip what piled up while the instance was down.
     */
    @Bean
    ApplicationRunner pushSubscriptions(EventBus events, PushHub hub, GatewayProperties properties) {
        return args -> {
            String group = "fleetloom-gateway-" + hostName();
            for (String type : properties.push().events()) {
                events.subscribeLive(type, group, JsonNode.class, hub::broadcast);
            }
        };
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "local";
        }
    }
}
