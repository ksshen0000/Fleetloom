package org.rcs3000.traffic.infra;

import java.time.Clock;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.rcs3000.contracts.fleet.OrderRefDto;
import org.rcs3000.contracts.fleet.RouteCommandDto;
import org.rcs3000.traffic.app.TrafficEngine;
import org.rcs3000.traffic.app.TrafficProperties;
import org.rcs3000.traffic.core.AStarPlanner;
import org.rcs3000.traffic.core.FleetGateway;
import org.rcs3000.traffic.core.MapProvider;
import org.rcs3000.traffic.core.TrafficController;
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
        return new ValkeyEventBus(connectionFactory, json, "rcs-traffic", clock);
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
        RestClient rest = builder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(rest)).build().createClient(type);
    }
}
