package org.rcs3000.dispatch.infra;

import java.time.Clock;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.rcs3000.common.event.EventBus;
import org.rcs3000.common.web.HttpClients;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.rcs3000.dispatch.app.CallbackProperties;
import org.rcs3000.dispatch.app.DispatchEngine;
import org.rcs3000.dispatch.app.DispatchProperties;
import org.rcs3000.dispatch.app.OrderCallbacks;
import org.rcs3000.dispatch.core.Dispatcher;
import org.rcs3000.dispatch.core.Ports;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "org.rcs3000.dispatch.infra", annotationClass = Mapper.class)
public class DispatchConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json, Clock clock) {
        return new ValkeyEventBus(connectionFactory, json, "rcs-dispatch", clock);
    }

    @Bean
    Ports.TemplateRepository templateRepository(OrderTemplateMapper mapper, JsonMapper json) {
        return new MybatisTemplateRepository(mapper, json);
    }

    @Bean(destroyMethod = "close")
    DispatchEngine dispatchEngine(TransportOrderMapper orders, VehicleSettingMapper settings, JsonMapper json,
            RestClient.Builder rest, DispatchProperties properties, EventBus events, Clock clock,
            Ports.TemplateRepository templates) {
        Dispatcher dispatcher = new Dispatcher(
                new MybatisOrderRepository(orders, json),
                templates,
                new MybatisVehicleSettingsRepository(settings, clock),
                HttpGateways.resource(client(rest, properties.resourceUrl(), HttpApis.ResourceApi.class)),
                HttpGateways.traffic(client(rest, properties.trafficUrl(), HttpApis.TrafficApi.class)),
                HttpGateways.fleet(client(rest, properties.fleetUrl(), HttpApis.FleetApi.class)),
                events, properties.toSettings(), clock);
        return new DispatchEngine(dispatcher);
    }

    @Bean
    OrderCallbacks orderCallbacks(CallbackProperties properties, JsonMapper json, RestClient.Builder rest) {
        return new OrderCallbacks(properties, json, rest);
    }

    @Bean
    SmartLifecycle dispatchLifecycle(DispatchEngine engine, EventBus events, DispatchProperties properties,
            OrderCallbacks callbacks) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                callbacks.start(events);
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
