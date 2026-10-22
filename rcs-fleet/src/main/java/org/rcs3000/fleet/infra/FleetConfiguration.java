package org.rcs3000.fleet.infra;

import java.time.Clock;

import org.rcs3000.common.event.EventBus;
import org.rcs3000.common.event.valkey.ValkeyEventBus;
import org.rcs3000.fleet.app.FleetProperties;
import org.rcs3000.fleet.app.FleetService;
import org.rcs3000.fleet.app.MessageRecorder;
import org.rcs3000.fleet.core.MessageLog;
import org.rcs3000.fleet.core.VehicleStatusCache;
import org.rcs3000.vda5050.client.MasterClient;
import org.rcs3000.vda5050.transport.hivemq.HiveMqTransport;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class FleetConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean(destroyMethod = "close")
    ValkeyEventBus eventBus(RedisConnectionFactory connectionFactory, JsonMapper json, Clock clock) {
        return new ValkeyEventBus(connectionFactory, json, "rcs-fleet", clock);
    }

    @Bean
    VehicleStatusCache vehicleStatusCache(StringRedisTemplate redis, JsonMapper json) {
        return new ValkeyVehicleStatusCache(redis, json);
    }

    @Bean
    MessageLog messageLog(StringRedisTemplate redis, FleetProperties properties) {
        return new ValkeyMessageLog(redis, properties.messageLog().retention());
    }

    @Bean
    FleetService fleetService(FleetProperties properties, EventBus events, VehicleStatusCache cache,
            MessageLog messageLog, Clock clock) {
        FleetProperties.Mqtt mqtt = properties.mqtt();
        MessageRecorder recorder = new MessageRecorder(messageLog, clock, properties.messageLog().stateInterval());
        MasterClient master = new MasterClient(new HiveMqTransport(mqtt.host(), mqtt.port(), mqtt.clientId()),
                mqtt.interfaceName(), clock, recorder);
        return new FleetService(master, events, cache, clock, properties.heartbeatTimeout());
    }

    /** Connects to the broker after the context is ready and disconnects on shutdown. */
    @Bean
    SmartLifecycle fleetLifecycle(FleetService fleet) {
        return new SmartLifecycle() {
            private volatile boolean running;

            @Override
            public void start() {
                fleet.start();
                running = true;
            }

            @Override
            public void stop() {
                fleet.stop();
                running = false;
            }

            @Override
            public boolean isRunning() {
                return running;
            }
        };
    }

    @Bean
    HeartbeatCheck heartbeatCheck(FleetService fleet) {
        return new HeartbeatCheck(fleet);
    }

    static class HeartbeatCheck {

        private final FleetService fleet;

        HeartbeatCheck(FleetService fleet) {
            this.fleet = fleet;
        }

        @Scheduled(fixedDelay = 1000)
        void check() {
            fleet.checkHeartbeats();
        }
    }
}
