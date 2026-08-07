package com.protify.portfolio.db;

import com.protify.portfolio.support.AbstractIntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2-B4 — {@code V11__seed_price_history.sql} seeds two years of real daily closes (plus a
 * documented synthetic fallback for the two instruments with no free real-data source — see
 * {@code scripts/backfill_prices.py}) for every V2-seeded instrument. A 100× GBX/GBP error is
 * invisible in an assertion nobody wrote (R14) — this one is written.
 */
class SeedPriceHistoryIT extends AbstractIntegrationTest {

    private static final int MIN_ROWS_PER_INSTRUMENT = 480;

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .load()
                .migrate();
    }

    @Test
    void everySeededInstrumentHasAtLeastTheMinimumRowCount() throws Exception {
        String sql = """
                SELECT i.symbol, COUNT(ph.id) AS row_count
                FROM instrument i
                LEFT JOIN price_history ph ON ph.instrument_id = i.id
                GROUP BY i.symbol
                HAVING row_count < %d
                """.formatted(MIN_ROWS_PER_INSTRUMENT);

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
                var statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            StringBuilder underSeeded = new StringBuilder();
            while (rs.next()) {
                underSeeded.append(rs.getString("symbol")).append('=').append(rs.getInt("row_count")).append(' ');
            }
            assertThat(underSeeded.toString()).as("instruments below %d rows", MIN_ROWS_PER_INSTRUMENT).isEmpty();
        }
    }

    @Test
    void noSeededGbpInstrumentHasACloseOverOneThousand() throws Exception {
        // A 100x GBX/GBP conversion error is exactly this: a GBP close in the low thousands
        // instead of tens/hundreds. Real LSE equities/ETFs here trade well under 1000 GBP.
        String sql = """
                SELECT COUNT(*) FROM price_history ph
                JOIN instrument i ON i.id = ph.instrument_id
                WHERE i.currency = 'GBP' AND ph.close_price > 1000
                """;
        assertThat(count(sql)).isZero();
    }

    @Test
    void everyPriceHistoryRowIsMarkedAsSeed() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM price_history WHERE source <> 'SEED'")).isZero();
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
