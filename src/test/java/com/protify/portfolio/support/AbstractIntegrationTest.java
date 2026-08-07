package com.protify.portfolio.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * One container per suite, reused across every {@code *IT} class (TEST_PLAN.md §6) — starting
 * MySQL per class costs minutes. {@code public} with {@code protected} members because
 * subclasses now span more than one package ({@code db}, {@code support}) now that everything
 * lives in a single module.
 */
@Testcontainers
public abstract class AbstractIntegrationTest {

    @Container
    protected static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withUrlParam("serverTimezone", "UTC")
            .withReuse(true);

    private static HikariDataSource dataSource;
    private static String migratedJdbcUrl;

    /**
     * Builds the pool and runs Flyway, shared by every subclass — but rebuilds both if the
     * container has been restarted since. {@code @Testcontainers} starts/stops a {@code static}
     * {@code @Container} field once per concrete test class, not once per JVM run, so a restart
     * between classes gives {@link #MYSQL} a new mapped port; without this check, every class
     * after the first would keep a dead pool pointed at the old one.
     */
    protected static synchronized NamedParameterJdbcTemplate jdbcTemplate() {
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
        if (dataSource == null || !MYSQL.getJdbcUrl().equals(migratedJdbcUrl)) {
            if (dataSource != null) {
                dataSource.close();
            }
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(MYSQL.getJdbcUrl());
            config.setUsername(MYSQL.getUsername());
            config.setPassword(MYSQL.getPassword());
            dataSource = new HikariDataSource(config);

            Flyway.configure()
                    .dataSource(dataSource)
                    .load()
                    .migrate();
            migratedJdbcUrl = MYSQL.getJdbcUrl();
        }
        return new NamedParameterJdbcTemplate(dataSource);
    }
}
