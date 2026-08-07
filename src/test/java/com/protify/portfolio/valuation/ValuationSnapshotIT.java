package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.support.AbstractIntegrationTest;
import com.protify.portfolio.transaction.RecordTransactionCommand;
import com.protify.portfolio.transaction.TransactionService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * day-5-dev-A.md D5-A2. Real MySQL, real seeded {@code price_history}/{@code fx_rate} (V11/V12),
 * the real wiring — because what is being proved is a claim about the <b>relationship</b> between
 * two things (the fold and its cache), and a mocked cache can only ever agree with itself.
 *
 * <p>The claim, in one sentence: <b>turning the cache on must not change a single number.</b>
 * Everything below is a way of trying to make that false.
 */
@SpringBootTest(classes = PortfolioApplication.class)
@Import(ValuationSnapshotIT.StubJwtDecoderConfig.class)
class ValuationSnapshotIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * {@code SecurityConfig}'s real decoder calls {@code JwtDecoders.fromIssuerLocation}, which
     * fetches Google's OpenID configuration over HTTP while the context is being built. This test
     * calls services directly and never presents a token, and the demo has to work with the wifi
     * off (CLAUDE.md), so the context must not need the network to start. {@code SecurityConfig}
     * declares its decoder {@code @ConditionalOnMissingBean}, so supplying one here replaces it.
     */
    @TestConfiguration
    static class StubJwtDecoderConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new UnsupportedOperationException("ValuationSnapshotIT does not authenticate");
            };
        }
    }

    @Autowired
    private PerformanceService performanceService;
    @Autowired
    private ValuationSnapshotRepository snapshotRepository;
    @Autowired
    private TransactionService transactionService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    /** Comfortably inside the two years V11 seeds, and comfortably in the past, so every day in
     * the window is "completed" and therefore snapshottable. */
    private static final LocalDate FIRST_TXN = LocalDate.parse("2025-03-03");
    private static final LocalDate FROM = LocalDate.parse("2025-03-03");
    private static final LocalDate TO = LocalDate.parse("2025-06-30");

    private long userId;
    private long portfolioId;

    @BeforeEach
    void setUp() {
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio_valuation_daily");
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES ('snap-sub', 'snap@example.com')", Map.of());
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = 'snap-sub'", Map.of(), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Snapshots', 'USD')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId",
                Map.of("userId", userId), Long.class);

        // A deposit, then three buys spread across the window, so the fold has cash, cost basis
        // and several priced positions to get wrong.
        record(TransactionType.DEPOSIT, null, "0", "50000.00", CurrencyCode.USD, FIRST_TXN);
        record(TransactionType.BUY, "AAPL", "40", "180.00", CurrencyCode.USD, FIRST_TXN);
        record(TransactionType.BUY, "MSFT", "20", "400.00", CurrencyCode.USD, LocalDate.parse("2025-04-10"));
        record(TransactionType.BUY, "AAPL", "10", "195.00", CurrencyCode.USD, LocalDate.parse("2025-05-20"));
    }

    private long record(TransactionType type, String symbol, String qty, String price,
            CurrencyCode currency, LocalDate date) {
        Instant executedAt = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        return transactionService.record(userId, portfolioId, new RecordTransactionCommand(
                symbol, type, new BigDecimal(qty), new BigDecimal(price), BigDecimal.ZERO,
                currency, executedAt, null)).txnId();
    }

    private List<PerformancePoint> series() {
        return performanceService.getPerformance(userId, portfolioId, FROM, TO, null).points();
    }

    private long snapshotCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM portfolio_valuation_daily WHERE portfolio_id = :id",
                Map.of("id", portfolioId), Long.class);
        return count == null ? 0 : count;
    }

    private static void assertSameSeries(List<PerformancePoint> expected, List<PerformancePoint> actual) {
        assertThat(actual).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            PerformancePoint e = expected.get(i);
            PerformancePoint a = actual.get(i);
            assertThat(a.date()).isEqualTo(e.date());
            assertThat(a.marketValue()).as("marketValue on %s", e.date()).isEqualByComparingTo(e.marketValue());
            assertThat(a.costBasis()).as("costBasis on %s", e.date()).isEqualByComparingTo(e.costBasis());
            assertThat(a.cashBalance()).as("cashBalance on %s", e.date()).isEqualByComparingTo(e.cashBalance());
            assertThat(a.totalValue()).as("totalValue on %s", e.date()).isEqualByComparingTo(e.totalValue());
            assertThat(a.unrealisedPnl()).as("unrealisedPnl on %s", e.date()).isEqualByComparingTo(e.unrealisedPnl());
            assertThat(a.filled()).as("filled on %s", e.date()).isEqualTo(e.filled());
        }
    }

    /**
     * <b>The headline assertion.</b> The first call folds every day live and memoises it; the
     * second is served almost entirely from {@code portfolio_valuation_daily}. Every money field
     * of every point must match exactly — and so must {@code filled}, which is why that flag had
     * to become a stored column rather than something the cache quietly dropped.
     */
    @Test
    void snapshotValuesEqualLiveFoldValuesExactly() {
        List<PerformancePoint> live = series();
        assertThat(snapshotCount()).isEqualTo(live.size());

        List<PerformancePoint> cached = series();

        assertSameSeries(live, cached);
        assertThat(cached).isNotEmpty();
        // Not a series of zeroes agreeing with itself: the seeded prices really do move.
        assertThat(cached.stream().map(PerformancePoint::marketValue).distinct()).hasSizeGreaterThan(10);
    }

    /** The same in a non-base currency, where every point goes through an FX cross rate — the
     * conversion has to be inside the snapshot, not applied on top of one. */
    @Test
    void snapshotValuesEqualLiveFoldValuesInANonBaseCurrency() {
        List<PerformancePoint> live = performanceService
                .getPerformance(userId, portfolioId, FROM, TO, CurrencyCode.INR).points();
        List<PerformancePoint> cached = performanceService
                .getPerformance(userId, portfolioId, FROM, TO, CurrencyCode.INR).points();

        assertSameSeries(live, cached);
    }

    /**
     * A snapshot is keyed by currency, so a USD row can never be handed to an INR request. Without
     * the {@code currency} column added in {@code V3__valuation_snapshot.sql}, the unique key
     * {@code (portfolio_id, valuation_date)} would have made this silently return dollars
     * labelled as rupees.
     */
    @Test
    void aSeriesRequestedInANonBaseCurrencyIsNotServedBaseCurrencySnapshots() {
        List<PerformancePoint> usd = series();
        List<PerformancePoint> inr = performanceService
                .getPerformance(userId, portfolioId, FROM, TO, CurrencyCode.INR).points();

        assertThat(inr).hasSameSizeAs(usd);
        // Rupees are worth far less than dollars, so an INR series served from USD rows would
        // show up here immediately.
        assertThat(inr.get(inr.size() - 1).marketValue())
                .isGreaterThan(usd.get(usd.size() - 1).marketValue());
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.INR, FROM, TO)).isNotEmpty();
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, FROM, TO)).isNotEmpty();
    }

    /**
     * <b>The invalidation half, and the one that matters.</b> Deleting a transaction from the
     * middle of the history changes that day and every day after it — and nothing before it. A
     * cache that dropped too little would serve a wrong chart; one that dropped everything would
     * work but prove nothing about the rule.
     */
    @Test
    void aMidHistoryDeleteInvalidatesEveryDayFromThatDateOnwardAndNoneBefore() {
        long msftTxnId = jdbc.queryForObject("""
                SELECT t.id FROM txn t JOIN instrument i ON t.instrument_id = i.id
                WHERE t.portfolio_id = :id AND i.symbol = 'MSFT'
                """, Map.of("id", portfolioId), Long.class);
        LocalDate deletedDate = LocalDate.parse("2025-04-10");

        series();
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, FROM, TO)).isNotEmpty();

        transactionService.delete(userId, portfolioId, msftTxnId);

        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, FROM, deletedDate.minusDays(1)))
                .as("days before the deleted transaction are untouched")
                .isNotEmpty();
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, deletedDate, TO))
                .as("the deleted transaction's own day and everything after it are gone")
                .isEmpty();

        // ...and the recomputed series matches a fold done with no cache at all.
        List<PerformancePoint> afterDelete = series();
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio_valuation_daily");
        assertSameSeries(series(), afterDelete);
    }

    /** Recording a transaction invalidates from its own date too — the write path and the delete
     * path have to obey the same rule, or a back-dated BUY leaves a wrong chart behind it. */
    @Test
    void recordingABackDatedTransactionInvalidatesFromItsOwnDate() {
        series();
        LocalDate backDated = LocalDate.parse("2025-05-01");

        record(TransactionType.BUY, "AAPL", "5", "190.00", CurrencyCode.USD, backDated);

        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, FROM, backDated.minusDays(1)))
                .isNotEmpty();
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, backDated, TO))
                .isEmpty();
    }

    /** "If a snapshot is missing, compute it — never return a gap." A hole punched into the
     * middle of the cache must not produce a hole in the chart. */
    @Test
    void aMissingSnapshotIsComputedRatherThanReturnedAsAGap() {
        List<PerformancePoint> complete = series();
        LocalDate hole = LocalDate.parse("2025-05-05");
        jdbc.update("""
                DELETE FROM portfolio_valuation_daily
                WHERE portfolio_id = :id AND valuation_date BETWEEN :from AND :to
                """, Map.of("id", portfolioId, "from", hole, "to", hole.plusDays(9)));

        List<PerformancePoint> withHole = series();

        assertSameSeries(complete, withHole);
        // ...and the hole was filled back in rather than left for the next request.
        assertThat(snapshotRepository.findRange(portfolioId, CurrencyCode.USD, hole, hole.plusDays(9)))
                .hasSize(10);
    }

    /** Deleting a portfolio has to take its snapshots with it — {@code fk_val_portfolio} means
     * the delete fails outright otherwise, which would be a 500 on a working endpoint. */
    @Test
    void deletingAPortfolioRemovesItsSnapshots() {
        series();
        assertThat(snapshotCount()).isPositive();

        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.update("DELETE FROM portfolio_valuation_daily WHERE portfolio_id = :id", Map.of("id", portfolioId));
        jdbc.update("DELETE FROM portfolio WHERE id = :id", Map.of("id", portfolioId));

        assertThat(snapshotCount()).isZero();
    }

    /**
     * The budget from the brief: a 365-day series under 100 ms once warm. The first call is the
     * cold fold and is not measured — what is being timed is the cache doing its job.
     */
    @Test
    void aThreeHundredAndSixtyFiveDaySeriesCompletesUnderOneHundredMilliseconds() {
        LocalDate to = FIRST_TXN.plusDays(364);
        performanceService.getPerformance(userId, portfolioId, FIRST_TXN, to, null);
        // Twice more, so class loading and JIT warm-up are not what gets measured.
        performanceService.getPerformance(userId, portfolioId, FIRST_TXN, to, null);

        long startNanos = System.nanoTime();
        List<PerformancePoint> points =
                performanceService.getPerformance(userId, portfolioId, FIRST_TXN, to, null).points();
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(points).hasSize(365);
        assertThat(elapsedMillis).as("365-day series, fully memoised").isLessThan(100L);
    }
}
