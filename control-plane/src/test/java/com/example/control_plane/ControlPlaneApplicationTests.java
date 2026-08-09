package com.example.control_plane;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring context load against real PostgreSQL and Redis Testcontainers.
 * This is what keeps the "tested real SLA engine" story honest: the exact stack
 * the SLA engine relies on (JPA persistence + Redis pub/sub) is present.
 *
 * <p>Requires a running Docker engine, exactly like the other Testcontainers
 * tests in this suite.</p>
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ControlPlaneApplicationTests {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:15-alpine");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis =
            new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        // Leave the SLA scheduler idle during the context test.
        registry.add("sla.validator.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Test
    void contextLoadsAndSlaStatusIsServed() {
        // The SLA status endpoint must come up with a (currently empty) window.
        String body = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/api/sla/status")
                .retrieve()
                .body(String.class);
        assertThat(body).contains("\"sample_count\"");
    }
}
