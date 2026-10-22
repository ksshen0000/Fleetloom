package org.rcs3000.gateway.security;

import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.rcs3000.common.api.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks the access tokens issued by rcs-resource (HS256, shared secret) and applies the role rules:
 * VIEWER may read, OPERATOR and ADMIN may also change things, only ADMIN manages users.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    static final String ISSUER = "rcs3000";
    static final String PUSH_PATH = "/ws";

    @Bean
    ReactiveJwtDecoder jwtDecoder(@Value("${rcs.security.jwt-secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("rcs.security.jwt-secret must be at least 32 bytes for HS256");
        }
        SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(ISSUER));
        return decoder;
    }

    @Bean
    LoginRateLimiter loginRateLimiter(@Value("${rcs.security.login-attempts:10}") int attempts,
            @Value("${rcs.security.login-window:1m}") java.time.Duration window, JsonMapper json) {
        return new LoginRateLimiter(attempts, window, java.time.Clock.systemUTC(), json);
    }

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder, JsonMapper json) {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("role");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter authentication = new JwtAuthenticationConverter();
        authentication.setJwtGrantedAuthoritiesConverter(roles);

        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(c -> c.requestCache(NoOpServerRequestCache.getInstance()))
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(a -> a
                        .pathMatchers(HttpMethod.POST, "/api/resource/v1/auth/login").permitAll()
                        .pathMatchers("/actuator/health/**", "/actuator/info").permitAll()
                        .pathMatchers("/actuator/**").hasRole("ADMIN")
                        // raw route commands are for rcs-traffic only: from outside they would bypass traffic control
                        .pathMatchers("/api/fleet/v1/vehicles/*/orders/**").denyAll()
                        .pathMatchers("/api/resource/v1/users/**", "/api/resource/v1/audit-logs/**").hasRole("ADMIN")
                        .pathMatchers(HttpMethod.PUT, "/api/*/v1/settings").hasRole("ADMIN")
                        .pathMatchers("/api/resource/v1/auth/**").authenticated()
                        .pathMatchers(HttpMethod.GET, "/api/**").authenticated()
                        .pathMatchers("/api/**").hasAnyRole("ADMIN", "OPERATOR")
                        .pathMatchers(HttpMethod.GET, PUSH_PATH).authenticated()
                        .anyExchange().denyAll())
                .oauth2ResourceServer(o -> o
                        .bearerTokenConverter(bearerTokens())
                        .jwt(j -> j.jwtDecoder(decoder)
                                .jwtAuthenticationConverter(new ReactiveJwtAuthenticationConverterAdapter(authentication)))
                        .authenticationEntryPoint((exchange, e) ->
                                error(exchange, json, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "login required"))
                        .accessDeniedHandler((exchange, e) ->
                                error(exchange, json, HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed for your role")))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((exchange, ex) ->
                                error(exchange, json, HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "login required"))
                        .accessDeniedHandler((exchange, ex) ->
                                error(exchange, json, HttpStatus.FORBIDDEN, "FORBIDDEN", "not allowed for your role")))
                .build();
    }

    /**
     * Tokens come in the Authorization header; only the WebSocket handshake, where browsers cannot set
     * headers, may pass it as {@code ?access_token=}.
     */
    private static ServerAuthenticationConverter bearerTokens() {
        ServerBearerTokenAuthenticationConverter header = new ServerBearerTokenAuthenticationConverter();
        ServerBearerTokenAuthenticationConverter headerOrQuery = new ServerBearerTokenAuthenticationConverter();
        headerOrQuery.setAllowUriQueryParameter(true);
        return exchange -> PUSH_PATH.equals(exchange.getRequest().getPath().value())
                ? headerOrQuery.convert(exchange)
                : header.convert(exchange);
    }

    private static Mono<Void> error(ServerWebExchange exchange, JsonMapper json, HttpStatus status, String code, String message) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer body = response.bufferFactory().wrap(json.writeValueAsBytes(ApiResponse.error(code, message, null)));
        return response.writeWith(Mono.just(body));
    }
}
