package com.protify.portfolio.support;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * A private, freshly migrated schema inside the container {@link AbstractIntegrationTest} already
 * runs — for the tests that assert on what the migrations themselves put in the database.
 *
 * <p>Those tests cannot use the shared default schema. Eleven {@code *IT} classes open with
 * {@code DELETE FROM app_user} / {@code portfolio} / {@code txn} / {@code holding} in
 * {@code @BeforeEach} to build their own fixtures, and JUnit does not promise an order, so
 * whether {@code V5__demo_seed.sql}'s rows are still there when an assertion runs would be a
 * coin toss. Deleting a schema nobody else touches removes the question rather than managing it.
 *
 * <p>Two things fall out of this for free: every call is a genuine "do all the migrations apply
 * to an <b>empty</b> schema?" run (day-6-dev-A.md D6-A3), and it costs no extra container — a
 * second {@code MySQLContainer} would have cost a MySQL startup per suite.
 *
 * <p>Connects as {@code root} to issue the {@code CREATE DATABASE}: Testcontainers' {@code test}
 * user is granted only on the {@code test} database by the MySQL image's entrypoint, while
 * {@code MySQLContainer} sets {@code MYSQL_ROOT_PASSWORD} to the same value as
 * {@link org.testcontainers.containers.MySQLContainer#getPassword()}.
 */
public final class FreshSchema {

    private static final String ROOT_USER = "root";

    private static final Map<String, HikariDataSource> POOLS = new HashMap<>();

    private FreshSchema() {
    }

    /**
     * Drops and recreates {@code schemaName}, migrates it from scratch, and returns a template
     * bound to it. Idempotent per schema name within a JVM: the first caller pays for the
     * migration, later ones share the pool, so two {@code *IT} classes can name the same schema
     * and see the same rows.
     */
    public static synchronized NamedParameterJdbcTemplate migratedInto(String schemaName) {
        return new NamedParameterJdbcTemplate(POOLS.computeIfAbsent(schemaName, FreshSchema::build));
    }

    private static HikariDataSource build(String schemaName) {
        if (!AbstractIntegrationTest.MYSQL.isRunning()) {
            AbstractIntegrationTest.MYSQL.start();
        }
        recreateSchema(schemaName);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrlFor(schemaName));
        config.setUsername(ROOT_USER);
        config.setPassword(AbstractIntegrationTest.MYSQL.getPassword());
        HikariDataSource dataSource = new HikariDataSource(config);

        migrate(dataSource);
        return dataSource;
    }

    /** Exposed so a test can assert on the migration outcome itself, not just its side effects. */
    public static Flyway migrate(DataSource dataSource) {
        Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();
        return flyway;
    }

    private static void recreateSchema(String schemaName) {
        // schemaName is a compile-time constant in every caller, never user input, but it is
        // still concatenated into DDL that cannot be parameterised — so it is validated, not
        // trusted (CLAUDE.md: never concatenate unchecked input into SQL).
        if (!schemaName.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("unsafe schema name: " + schemaName);
        }
        try (Connection connection = DriverManager.getConnection(
                serverUrl(), ROOT_USER, AbstractIntegrationTest.MYSQL.getPassword());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + schemaName);
            statement.execute("CREATE DATABASE " + schemaName + " CHARACTER SET utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("could not create test schema " + schemaName, e);
        }
    }

    /** The container's JDBC URL with the default database swapped for {@code schemaName}. */
    private static String jdbcUrlFor(String schemaName) {
        String url = AbstractIntegrationTest.MYSQL.getJdbcUrl();
        int lastSlash = url.lastIndexOf('/');
        int query = url.indexOf('?', lastSlash);
        String params = query < 0 ? "" : url.substring(query);
        return url.substring(0, lastSlash + 1) + schemaName + params;
    }

    private static String serverUrl() {
        return jdbcUrlFor("mysql");
    }
}
