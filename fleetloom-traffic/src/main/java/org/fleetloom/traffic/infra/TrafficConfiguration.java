package org.fleetloom.traffic.infra;

import java.time.Clock;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.common.event.valkey.ValkeyEventBus;
import org.fleetloom.common.web.HttpClients;
import org.fleetloom.contracts.fleet.OrderRefDto;
import org.fleetloom.contracts.fleet.RouteCommandDto;
import org.fleetloom.traffic.app.TrafficEngine;
import org.fleetloom.traffic.app.TrafficProperties;
import org.fleetloom.traffic.core.AStarPlanner;
import org.fleetloom.traffic.core.FleetGateway;
import org.fleetloom.traffic.core.MapProvider;
import org.fleetloom.traffic.core.TrafficController;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
public class TrafficConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json, Clock clock) {
        return new ValkeyEventBus(connectionFactory, json, "fleetloom-traffic", clock);
    }

    @Bean
    FleetGateway fleetGateway(RestClient.Builder builder, TrafficProperties properties) {
        FleetHttpApi api = client(builder, properties.fleetUrl(), FleetHttpApi.class);
        return new FleetGateway() {
            @Override
            public OrderRefDto sendRoute(String vehicleCode, RouteCommandDto route) {
                return api.sendRoute(vehicleCode, route).data();
            }

            @Override
            public OrderRefDto extendRoute(String vehicleCode, RouteCommandDto route) {
                return api.extendRoute(vehicleCode, route).data();
            }

            @Override
            public void cancelOrder(String vehicleCode) {
                api.cancelOrder(vehicleCode);
            }
        };
    }

    @Bean
    MapProvider mapProvider(RestClient.Builder builder, TrafficProperties properties) {
        ResourceHttpApi api = client(builder, properties.resourceUrl(), ResourceHttpApi.class);
        return mapCode -> api.activeTopology(mapCode).data();
    }

    @Bean(destroyMethod = "close")
    TrafficEngine trafficEngine(FleetGateway fleet, MapProvider maps, EventBus events, TrafficProperties properties,
            Clock clock) {
        return new TrafficEngine(new TrafficController(new AStarPlanner(properties.turnPenaltyPerRadian()), fleet, maps,
                events, properties.toSettings(), clock));
    }

    @Bean
    SmartLifecycle trafficLifecycle(TrafficEngine engine, EventBus events, TrafficProperties properties) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                engine.start(events, properties.tickMillis());
                running = true;
            }

            @Override
            public void stop() {
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    private static <T> T client(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient rest = HttpClients.withTimeouts(builder, baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(type);
    }
}
