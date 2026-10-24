package org.fleetloom.gateway;

import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class RouteConfiguration {

    /** One route per service; paths are passed through unchanged since every service owns {@code /api/<name>}. */
    @Bean
    RouteLocator serviceRoutes(RouteLocatorBuilder builder, GatewayProperties properties) {
        RouteLocatorBuilder.Builder routes = builder.routes();
        properties.services().forEach((name, uri) ->
                routes.route(name, r -> r.path("/api/" + name + "/**").uri(uri)));
        return routes.build();
    }
}
