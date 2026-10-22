package org.rcs3000.gateway.audit;

import java.time.Clock;

import org.rcs3000.common.event.EventBus;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

@Configuration(proxyBeanMethods = false)
public class AuditConfiguration {

    @Bean
    AuditWebFilter auditWebFilter(EventBus events, ReactiveJwtDecoder tokens) {
        return new AuditWebFilter(events, tokens, Clock.systemUTC());
    }
}
