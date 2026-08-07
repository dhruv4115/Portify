package com.protify.portfolio.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Every migration in {@code portfolio-db} applies to an empty schema, in order, with no
 * duplicate version number (ADR-0007), and every money column is exactly
 * {@code DECIMAL(19,4)} — the typo that would otherwise silently truncate money.
 */
class FlywayMigrationIT extends AbstractIntegrationTest {

    private static final List<String[]> MONEY_COLUMNS = List.of(
            new String[] {"txn", "price"},
            new String[] {"txn", "fees"},
            new String[] {"holding", "avg_cost"},
            new String[] {"holding", "realised_pnl"},
            new String[] {"price_history", "close_price"},
            new String[] {"portfolio_valuation_daily", "market_value"},
            new String[] {"portfolio_valuation_daily", "cost_basis"},
            new String[] {"portfolio_valuation_daily", "cash_balance"},
            new String[] {"portfolio_valuation_daily", "unrealised_pnl"}
    );

    private static final List<String[]> QUANTITY_COLUMNS = List.of(
            new String[] {"txn", "quantity"},
            new String[] {"holding", "quantity"}
    );

    private static Flyway flyway;
    private static MigrateResult migrateResult;

    @BeforeAll
    static void migrate() {
        flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load();
        migrateResult = flyway.migrate();
    }

    @Test
    void shouldApplyAllMigrationsToAnEmptySchema() {
        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrationsExecuted).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldHaveNoOutOfOrderOrDuplicateVersions() {
        MigrationInfo[] all = flyway.info().all();

        List<String> versions = Arrays.stream(all)
                .map(info -> info.getVersion().toString())
                .toList();

        assertThat(versions).doesNotHaveDuplicates();
        assertThat(Arrays.stream(all)).noneMatch(info -> info.getState() == MigrationState.OUT_OF_ORDER);
    }

    @Test
    void everyMoneyColumnShouldBeDecimalNineteenFour() throws Exception {
        Map<String, int[]> precisionAndScale = readNumericColumns();

        for (String[] column : MONEY_COLUMNS) {
            String key = column[0] + "." + column[1];
            int[] ps = precisionAndScale.get(key);

            assertThat(ps)
                    .as("column %s must exist", key)
                    .isNotNull();
            assertThat(ps[0]).as("%s numeric_precision", key).isEqualTo(19);
            assertThat(ps[1]).as("%s numeric_scale", key).isEqualTo(4);
        }
    }

    @Test
    void everyQuantityColumnShouldBeDecimalNineteenSix() throws Exception {
        Map<String, int[]> precisionAndScale = readNumericColumns();

        for (String[] column : QUANTITY_COLUMNS) {
            String key = column[0] + "." + column[1];
            int[] ps = precisionAndScale.get(key);

            assertThat(ps)
                    .as("column %s must exist", key)
                    .isNotNull();
            assertThat(ps[0]).as("%s numeric_precision", key).isEqualTo(19);
            assertThat(ps[1]).as("%s numeric_scale", key).isEqualTo(6);
        }
    }

    private Map<String, int[]> readNumericColumns() throws Exception {
        Map<String, int[]> result = new HashMap<>();
        String sql = """
                SELECT table_name, column_name, numeric_precision, numeric_scale
                FROM information_schema.columns
                WHERE table_schema = DATABASE() AND data_type = 'decimal'
                """;

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                String key = rs.getString("table_name") + "." + rs.getString("column_name");
                result.put(key, new int[] {rs.getInt("numeric_precision"), rs.getInt("numeric_scale")});
            }
        }
        return result;
    }
}
