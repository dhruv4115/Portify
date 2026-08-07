package com.protify.portfolio.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.NoOpDataSourceTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * D1-C4: springdoc must expose a valid schema containing {@code /me} and the {@code
 * bearer-jwt} security scheme. DataSource/Flyway excluded — same reasoning as
 * {@code ApplicationContextLoadsTest}, this just needs the web layer, not a database.
 *
 * <p>{@link NoOpDataSourceTestConfig} supplies a stub {@code DataSource} so the domain
 * repositories that landed on Day 2 can still autowire in this DataSource-less context.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration")
@Import(NoOpDataSourceTestConfig.class)
class OpenApiDocsTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void apiDocsContainMeAndTheBearerJwtSecurityScheme() {
        ResponseEntity<String> response = restTemplate.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"/api/v1/me\"");
        assertThat(body).contains("bearer-jwt");
        assertThat(body).contains("\"bearerFormat\":\"JWT\"");
    }

    @Test
    void swaggerUiIsReachable() {
        ResponseEntity<String> response = restTemplate.getForEntity("/swagger-ui/index.html", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
