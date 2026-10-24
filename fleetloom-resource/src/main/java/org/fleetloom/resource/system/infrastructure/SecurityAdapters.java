package org.fleetloom.resource.system.infrastructure;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.fleetloom.resource.system.domain.PasswordHasher;
import org.fleetloom.resource.system.domain.TokenIssuer;
import org.fleetloom.resource.system.domain.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * BCrypt password hashing and HS256-signed access tokens. The signing secret is shared with the
 * gateway through {@code fleetloom.security.jwt-secret} (environment variable {@code FLEETLOOM_JWT_SECRET}).
 */
@Configuration(proxyBeanMethods = false)
public class SecurityAdapters {

    static final String ISSUER = "fleetloom";

    @Bean
    PasswordHasher passwordHasher() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        return new PasswordHasher() {
            @Override
            public String hash(String rawPassword) {
                return encoder.encode(rawPassword);
            }

            @Override
            public boolean matches(String rawPassword, String hash) {
                return rawPassword != null && encoder.matches(rawPassword, hash);
            }
        };
    }

    @Bean
    TokenIssuer tokenIssuer(@Value("${fleetloom.security.jwt-secret}") String secret,
            @Value("${fleetloom.security.token-ttl:12h}") Duration ttl) {
        SecretKey key = secretKey(secret);
        NimbusJwtEncoder encoder = NimbusJwtEncoder.withSecretKey(key).build();
        return (User user, Instant now) -> {
            Instant expiresAt = now.plus(ttl);
            JwtClaimsSet claims = JwtClaimsSet.builder()
                    .issuer(ISSUER)
                    .subject(user.username())
                    .claim("name", user.displayName())
                    .claim("role", user.role().name())
                    .issuedAt(now)
                    .expiresAt(expiresAt)
                    .build();
            String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                    .getTokenValue();
            return new TokenIssuer.IssuedToken(token, expiresAt);
        };
    }

    static SecretKey secretKey(String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("fleetloom.security.jwt-secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(bytes, "HmacSHA256");
    }
}
