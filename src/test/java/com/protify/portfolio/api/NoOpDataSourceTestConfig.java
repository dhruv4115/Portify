package com.protify.portfolio.api;

import javax.sql.DataSource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Supplies just enough of a {@link DataSource} bean for {@code NamedParameterJdbcTemplate}
 * (needed since Day 2's domain repositories exist now) to autowire in the Docker-free smoke
 * tests that exclude {@code DataSourceAutoConfiguration} on purpose ({@code
 * ApplicationContextLoadsTest}, {@code OpenApiDocsTest}). Never actually opens a connection —
 * neither test calls a repository — so no real driver or database is required.
 */
@TestConfiguration
public class NoOpDataSourceTestConfig {

    @Bean
    public DataSource dataSource() {
        return new DriverManagerDataSource("jdbc:mysql://unused-in-tests/unused");
    }
}
