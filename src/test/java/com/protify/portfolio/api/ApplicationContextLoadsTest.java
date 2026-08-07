package com.protify.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * D1-C1's smoke test: the shell boots and {@code /actuator/health} answers. DataSource and
 * Flyway autoconfiguration are excluded deliberately — this test's job is to prove the
 * application shell (component scan, web config, actuator exposure) is wired correctly, not to
 * re-prove DB connectivity. Real DB wiring already has its own coverage
 * ({@code FlywayMigrationIT}, {@code SeedDataIT}), and this way the smoke test needs neither
 * Docker nor a local MySQL to run — every developer gets it on every {@code mvn verify}.
 *
 * <p>{@link NoOpDataSourceTestConfig} supplies a stub {@code DataSource} so the domain
 * repositories that landed on Day 2 (which need {@code NamedParameterJdbcTemplate}) can still
 * autowire in this DataSource-less context; it's never connected to.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                // The stub DataSource from NoOpDataSourceTestConfig exists only to satisfy bean
                // wiring — it's never connected to, so the DB health check must stay off or
                // /actuator/health reports DOWN against a host that was never meant to resolve.
                "management.health.db.enabled=false"
        })
@Import(NoOpDataSourceTestConfig.class)
class ApplicationContextLoadsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointReturnsUp() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }
}
