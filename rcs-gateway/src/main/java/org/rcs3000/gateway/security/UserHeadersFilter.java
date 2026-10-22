package org.rcs3000.gateway.security;

import java.util.Optional;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Tells the services who is calling. Whatever the client sent in these headers is dropped, so the
 * services can trust them as long as they are only reachable through the gateway.
 */
@Component
public class UserHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_NAME = "X-User-Name";
    public static final String USER_ROLE = "X-User-Role";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .cast(JwtAuthenticationToken.class)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(user -> {
                    ServerHttpRequest request = exchange.getRequest().mutate().headers(h -> {
                        h.remove(USER_NAME);
                        h.remove(USER_ROLE);
                        user.ifPresent(u -> {
                            h.set(USER_NAME, u.getName());
                            h.set(USER_ROLE, u.getToken().getClaimAsString("role"));
                        });
                    }).build();
                    return chain.filter(exchange.mutate().request(request).build());
                });
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
