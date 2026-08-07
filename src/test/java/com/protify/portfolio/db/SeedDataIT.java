package com.protify.portfolio.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.support.AbstractIntegrationTest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * V2 seeds instruments across four currencies and six asset types, proving the model is not
 * US-equity-only (day-1-dev-A.md D1-A6).
 *
 * <p>Note: the task prose says "18 instruments", but the table it gives (4+2+1+1+1 USD ·
 * 3+1+1 INR · 2+1 GBP · 1+1 EUR) lists 19 distinct tickers. V2 seeds exactly what the table
 * names; this test asserts 19 to match what was actually built. Worth confirming against the
 * customer's expectation — flagged rather than silently dropping a named instrument to force 18.
 */
class SeedDataIT extends AbstractIntegrationTest {

    private static final int EXPECTED_ROW_COUNT = 19;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    @Test
    void shouldSeedExpectedInstrumentCount() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM instrument")).isEqualTo(EXPECTED_ROW_COUNT);
    }

    @Test
    void shouldSpanAtLeastFourCurrencies() throws Exception {
        assertThat(count("SELECT COUNT(DISTINCT currency) FROM instrument")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void shouldSpanAtLeastSixAssetTypes() throws Exception {
        assertThat(count("SELECT COUNT(DISTINCT asset_type) FROM instrument")).isGreaterThanOrEqualTo(6);
    }

    @Test
    void everyRowShouldHaveExchangeAndSector() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM instrument WHERE exchange IS NULL OR sector IS NULL")).isZero();
    }

    private int count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
