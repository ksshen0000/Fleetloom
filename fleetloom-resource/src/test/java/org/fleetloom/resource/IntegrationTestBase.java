package org.fleetloom.resource;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared PostgreSQL and Valkey for all integration tests of this service: started once per JVM, so
 * the Spring context is cached across test classes. Skipped when Docker is not available.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

    @SuppressWarnings("resource")
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:8.1-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        POSTGRES.start();
        VALKEY.start();
        registry.add("spring.datasource.url",
                () -> POSTGRES.getJdbcUrl() + "&currentSchema=resource&stringtype=unspecified");
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
    }
}
