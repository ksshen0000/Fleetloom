package org.fleetloom.gateway.audit;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import org.fleetloom.common.event.EventBus;
import org.fleetloom.contracts.audit.AuditRecordedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Records every changing API request (anything but GET, HEAD and OPTIONS) as an audit event. Runs
 * before the security filters so refused requests are recorded too; the user is taken from the token,
 * verified again here, because the security context is not visible this far out.
 */
public class AuditWebFilter implements WebFilter, Ordered {

    /** Ahead of Spring Security's filter chain (order -100). */
    static final int ORDER = -200;
    private static final Set<HttpMethod> READS = Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS);
    private static final Logger log = LoggerFactory.getLogger(AuditWebFilter.class);

    private final EventBus events;
    private final ReactiveJwtDecoder tokens;
    private final Clock clock;

    public AuditWebFilter(EventBus events, ReactiveJwtDecoder tokens, Clock clock) {
        this.events = events;
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (READS.contains(request.getMethod()) || !request.getPath().value().startsWith("/api/")) {
            return chain.filter(exchange);
        }
        Instant at = clock.instant();
        long started = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> record(exchange, at, (System.nanoTime() - started) / 1_000_000));
    }

    private void record(ServerWebExchange exchange, Instant at, long durationMs) {
        ServerHttpRequest request = exchange.getRequest();
        var status = exchange.getResponse().getStatusCode();
        InetSocketAddress remote = request.getRemoteAddress();
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        String clientIp = forwarded != null ? forwarded.split(",")[0].trim()
                : remote == null ? null : remote.getAddress().getHostAddress();
        user(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION))
                .publishOn(Schedulers.boundedElastic())
                .subscribe(jwt -> events.publish(AuditRecordedEvent.TYPE, new AuditRecordedEvent(at,
                                jwt.map(Jwt::getSubject).orElse(null), jwt.map(j -> j.getClaimAsString("role")).orElse(null),
                                request.getMethod().name(), request.getPath().value(), request.getURI().getRawQuery(),
                                status == null ? 0 : status.value(), durationMs, clientIp)),
                        e -> log.warn("could not record audit event: {}", e.getMessage()));
    }

    private Mono<Optional<Jwt>> user(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Mono.just(Optional.empty());
        }
        return tokens.decode(authorization.substring(7).trim()).map(Optional::of).onErrorReturn(Optional.empty());
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
