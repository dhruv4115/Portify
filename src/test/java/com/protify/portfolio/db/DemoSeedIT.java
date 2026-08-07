package com.protify.portfolio.db;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.support.AbstractIntegrationTest;
import com.protify.portfolio.support.FreshSchema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * day-6-dev-A.md D6-A1 — {@code V5__demo_seed.sql} produces an interesting portfolio from a cold
 * start, deterministically. Every claim the showcase makes about the demo data is asserted here
 * rather than eyeballed, because "the chart looked right on my machine yesterday" is not a
 * property a rehearsal can rely on.
 *
 * <p>Runs against its own schema ({@link FreshSchema}) — the shared one is emptied by whichever
 * of the eleven fixture-building {@code *IT} classes happens to run first.
 */
class DemoSeedIT extends AbstractIntegrationTest {

    private static final String DEMO_SUB = "demo-user-1";
    private static final String SECOND_SUB = "demo-user-2";

    private static NamedParameterJdbcTemplate jdbc;
    private static long growthId;

    @BeforeAll
    static void migrate() {
        jdbc = FreshSchema.migratedInto("demo_seed_it");
        growthId = jdbc.queryForObject("""
                SELECT p.id FROM portfolio p
                JOIN app_user u ON u.id = p.user_id
                WHERE u.google_sub = :sub AND p.name = 'Growth'
                """, Map.of("sub", DEMO_SUB), Long.class);
    }

    @Test
    void demoPortfolioExistsWithInrBaseCurrency() {
        String baseCurrency = jdbc.queryForObject(
                "SELECT base_currency FROM portfolio WHERE id = :id",
                Map.of("id", growthId), String.class);

        assertThat(baseCurrency).isEqualTo("INR");
    }

    /**
     * The cross-user 404 prop. This user owning a portfolio is the entire point of seeding them:
     * without a real row to ask for, the live demonstration is asking for an id that does not
     * exist, which proves nothing about per-user scoping (CLAUDE.md non-negotiable #3).
     */
    @Test
    void secondUserExistsWithTheirOwnPortfolio() {
        Long otherPortfolioId = jdbc.queryForObject("""
                SELECT p.id FROM portfolio p
                JOIN app_user u ON u.id = p.user_id
                WHERE u.google_sub = :sub
                """, Map.of("sub", SECOND_SUB), Long.class);

        assertThat(otherPortfolioId).isNotNull().isNotEqualTo(growthId);
        assertThat(count("SELECT COUNT(*) FROM txn WHERE portfolio_id = " + otherPortfolioId))
                .as("the second portfolio needs holdings of its own, or the 404 is indistinguishable from an empty one")
                .isPositive();
    }

    @Test
    void shouldHaveAtLeastTwentyTransactions() {
        assertThat(txnCount("")).isGreaterThanOrEqualTo(20);
    }

    @Test
    void shouldSpanAtLeastThreeCurrencies() {
        assertThat(count("SELECT COUNT(DISTINCT currency) FROM txn WHERE portfolio_id = " + growthId))
                .isGreaterThanOrEqualTo(3);
    }

    @Test
    void shouldSpanAtLeastEighteenMonths() {
        LocalDate first = jdbc.queryForObject(
                "SELECT DATE(MIN(executed_at)) FROM txn WHERE portfolio_id = :id",
                Map.of("id", growthId), LocalDate.class);
        LocalDate last = jdbc.queryForObject(
                "SELECT DATE(MAX(executed_at)) FROM txn WHERE portfolio_id = :id",
                Map.of("id", growthId), LocalDate.class);

        assertThat(ChronoUnit.MONTHS.between(first, last))
                .as("18 months of chart history, from %s to %s", first, last)
                .isGreaterThanOrEqualTo(17);
    }

    @Test
    void shouldRealiseANonZeroProfit() {
        BigDecimal realised = jdbc.queryForObject(
                "SELECT SUM(realised_pnl) FROM holding WHERE portfolio_id = :id",
                Map.of("id", growthId), BigDecimal.class);

        assertThat(realised).isNotNull();
        assertThat(realised.signum()).as("realised P&L %s should be a visible profit", realised).isPositive();
    }

    @Test
    void shouldHaveAtLeastOneClosedPosition() {
        assertThat(count("SELECT COUNT(*) FROM holding WHERE portfolio_id = " + growthId
                + " AND quantity = 0"))
                .as("a position closed to zero is what `includeZero` is demonstrated with")
                .isPositive();

        assertThat(count("SELECT COUNT(*) FROM holding WHERE portfolio_id = " + growthId
                + " AND quantity = 0 AND realised_pnl = 0"))
                .as("a closed position must keep the P&L it realised on the way out")
                .isZero();
    }

    @Test
    void shouldIncludeADepositAndADividend() {
        assertThat(txnCount(" AND txn_type = 'DEPOSIT'")).isPositive();
        assertThat(txnCount(" AND txn_type = 'DIVIDEND'")).isPositive();
        assertThat(txnCount(" AND txn_type = 'SELL'"))
                .as("both a profit-taking SELL and a sell-to-zero")
                .isGreaterThanOrEqualTo(2);
    }

    /** DEPOSIT and WITHDRAWAL carry no instrument; everything else must (REFERENCE_DESIGN §3). */
    @Test
    void cashTransactionsShouldCarryNoInstrumentAndZeroQuantity() {
        assertThat(txnCount(" AND txn_type IN ('DEPOSIT', 'WITHDRAWAL')"
                + " AND (instrument_id IS NOT NULL OR quantity <> 0)")).isZero();
        assertThat(txnCount(" AND txn_type NOT IN ('DEPOSIT', 'WITHDRAWAL') AND instrument_id IS NULL"))
                .isZero();
    }

    /**
     * A transaction on a date with no seeded close is a hole the valuation layer has to
     * forward-fill across at exactly the point the chart moves — which is where a demo-day
     * "why is that flat?" question comes from. Cheaper to assert than to explain.
     */
    @Test
    void everyTradeDateShouldHaveASeededPriceAndFxRate() {
        List<String> gaps = jdbc.queryForList("""
                SELECT CONCAT(i.symbol, ' @ ', DATE(t.executed_at)) AS gap
                FROM txn t
                JOIN instrument i ON i.id = t.instrument_id
                LEFT JOIN price_history ph
                       ON ph.instrument_id = t.instrument_id AND ph.price_date = DATE(t.executed_at)
                WHERE ph.id IS NULL
                """, Map.of(), String.class);

        assertThat(gaps).as("trade dates with no seeded close").isEmpty();

        List<String> fxGaps = jdbc.queryForList("""
                SELECT CONCAT(t.currency, ' @ ', DATE(t.executed_at)) AS gap
                FROM txn t
                LEFT JOIN fx_rate fx
                       ON fx.base_ccy = 'USD' AND fx.quote_ccy = t.currency
                      AND fx.rate_date = DATE(t.executed_at)
                WHERE t.currency <> 'USD' AND fx.id IS NULL
                """, Map.of(), String.class);

        assertThat(fxGaps).as("trade dates with no seeded FX rate").isEmpty();
    }

    /**
     * The determinism the acceptance criterion actually turns on: the migration must not contain
     * a clock. A {@code NOW()} default would make the 18-month window slide every time the
     * database is rebuilt, and the rehearsal would stop matching the showcase.
     */
    @Test
    void everySeededTimestampShouldBeAFixedLiteral() {
        assertThat(count("SELECT COUNT(*) FROM txn WHERE DATE(executed_at) > '2026-07-31'"))
                .as("no transaction may drift past the end of the seeded price history")
                .isZero();
        // TIME_FORMAT, not TIME(): the column is DATETIME(6), so TIME() yields '09:15:00.000000'
        // and every row would "differ" from the literal below on the microseconds alone.
        assertThat(count("SELECT COUNT(*) FROM txn WHERE TIME_FORMAT(executed_at, '%H:%i:%s') "
                + "NOT IN ('09:15:00', '10:00:00', '10:30:00', '11:00:00')"))
                .as("every executed_at is one of the fixed literal times V5 writes")
                .isZero();
    }

    private int txnCount(String extraPredicate) {
        return count("SELECT COUNT(*) FROM txn WHERE portfolio_id = " + growthId + extraPredicate);
    }

    private int count(String sql) {
        Integer value = jdbc.queryForObject(sql, Map.of(), Integer.class);
        return value == null ? 0 : value;
    }
}
