package org.rcs3000.gateway.security;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.rcs3000.common.api.ApiResponse;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slows down password guessing: after {@code attempts} failed logins from one client address within
 * {@code window}, further attempts get 429 until the window has passed. Successful logins do not
 * count, so people and scripts that log in often are not locked out. Kept in memory: with several
 * gateway instances each one counts on its own, which still caps guessing.
 */
public class LoginRateLimiter implements WebFilter, Ordered {

    static final String LOGIN_PATH = "/api/resource/v1/auth/login";
    /** Before auditing and security, so refused attempts cost nothing downstream. */
    static final int ORDER = -300;
    private static final int MAX_TRACKED = 100_000;

    private final int attempts;
    private final Duration window;
    private final Clock clock;
    private final JsonMapper json;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public LoginRateLimiter(int attempts, Duration window, Clock clock, JsonMapper json) {
        this.attempts = attempts;
        this.window = window;
        this.clock = clock;
        this.json = json;
    }

    private record Window(Instant start, int count) {
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != HttpMethod.POST || !LOGIN_PATH.equals(request.getPath().value())) {
            return chain.filter(exchange);
        }
        Instant now = clock.instant();
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(w -> w.start().plus(window).isBefore(now));
        }
        String client = client(request);
        Window w = windows.get(client);
        if (w == null || !w.start().plus(window).isAfter(now) || w.count() < attempts) {
            return chain.filter(exchange).doFinally(signal -> {
                if (exchange.getResponse().getStatusCode() == HttpStatus.UNAUTHORIZED) {
                    failed(client);
                }
            });
        }
        long retryAfter = Math.max(1, Duration.between(now, w.start().plus(window)).toSeconds());
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        response.getHeaders().set("Retry-After", Long.toString(retryAfter));
        byte[] body = json.writeValueAsBytes(ApiResponse.error("TOO_MANY_LOGIN_ATTEMPTS",
                "too many login attempts, try again in %d s".formatted(retryAfter), null));
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    private void failed(String client) {
        Instant now = clock.instant();
        windows.compute(client, (ip, old) -> old == null || !old.start().plus(window).isAfter(now)
                ? new Window(now, 1) : new Window(old.start(), old.count() + 1));
    }

    /** The gateway may sit behind nginx, which passes the browser's address on. */
    private static String client(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
