package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.valuation.spi.FxConversion;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * day-5-dev-A.md D5-A1. Unit tests, every collaborator mocked — the series is handed in, so what
 * is under test is the analytics arithmetic and nothing else.
 *
 * <p><b>The fixture is built by hand and asserted to four decimal places</b>, because this is the
 * one place where "the code agrees with itself" is easiest to mistake for correctness: every
 * number a returns calculation produces looks plausible. The full working is written out in
 * {@link #shouldChainLinkSubPeriodReturnsAcrossAMidPeriodDeposit}'s javadoc so a reviewer can
 * check it without running anything.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;

    @Mock
    private PerformanceService performanceService;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private FxConversion fxConversion;

    private AnalyticsService service;

    @BeforeEach
    void setUp() {
        service = new AnalyticsService(performanceService, transactionRepository, fxConversion);
        // No FX rows: tradeDateFx falls back to identity, which is what a single-currency
        // portfolio does in production too.
        lenient().when(fxConversion.rate(any(), any(), any())).thenReturn(Optional.empty());
        lenient().when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- fixtures

    private static PerformancePoint point(String date, String totalValue) {
        BigDecimal total = new BigDecimal(totalValue);
        return new PerformancePoint(LocalDate.parse(date), total, BigDecimal.ZERO, BigDecimal.ZERO,
                total, BigDecimal.ZERO, false);
    }

    private static Txn transfer(TransactionType type, String amount, String date) {
        return new Txn(1L, PORTFOLIO_ID, null, type, BigDecimal.ZERO, new BigDecimal(amount),
                BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(date + "T00:00:00Z"), null, null);
    }

    /** Stubs the series {@link AnalyticsService} will read, with a summary whose
     * {@code netContributions} is passed straight through to the result. */
    private void givenSeries(List<PerformancePoint> points, String netContributions) {
        LocalDate from = points.isEmpty() ? LocalDate.parse("2026-01-01") : points.get(0).date();
        LocalDate to = points.isEmpty() ? LocalDate.parse("2026-01-06") : points.get(points.size() - 1).date();
        BigDecimal start = points.isEmpty() ? BigDecimal.ZERO : points.get(0).totalValue();
        BigDecimal end = points.isEmpty() ? BigDecimal.ZERO : points.get(points.size() - 1).totalValue();
        PerformanceSummary summary = new PerformanceSummary(start, end, end.subtract(start), null,
                new BigDecimal(netContributions));
        when(performanceService.getPerformance(USER_ID, PORTFOLIO_ID, from, to, null))
                .thenReturn(new PerformanceResult(PORTFOLIO_ID, CurrencyCode.USD, from, to, points,
                        summary, null, null, false));
    }

    private AnalyticsResult analyse(List<PerformancePoint> points) {
        return service.analyse(USER_ID, PORTFOLIO_ID, points.get(0).date(),
                points.get(points.size() - 1).date(), null);
    }

    // ---------------------------------------------------------------- the spreadsheet

    /**
     * <b>The hand-built fixture.</b> Six daily closes with a ₹1,000 deposit on day three:
     *
     * <pre>
     * date         V (close)   flow      r = (V − F) / V(prev) − 1
     * 2026-01-01    1000.0000     —      —              (opening value)
     * 2026-01-02    1100.0000     —      1100 / 1000 − 1 = +0.10
     * 2026-01-03    2155.0000   +1000    (2155 − 1000) / 1100 − 1 = 1155/1100 − 1 = +0.05
     * 2026-01-04    1939.5000     —      1939.50 / 2155 − 1 = −0.10
     * 2026-01-05    1978.2900     —      1978.29 / 1939.50 − 1 = +0.02
     * 2026-01-06    2057.4216     —      2057.4216 / 1978.29 − 1 = +0.04
     *
     * TWR   = 1.10 × 1.05 × 0.90 × 1.02 × 1.04 − 1
     *       = 1.155 × 0.90 = 1.0395 → × 1.02 = 1.060290 → × 1.04 = 1.10270160
     *       = 0.10270160                                        → 10.2702 %
     *
     * naive = (2057.4216 − 1000) / 1000 = 1.0574216                → 105.7422 %
     * </pre>
     *
     * <p>The two numbers differ by a factor of ten, and the entire difference is the user's own
     * deposit. That is the whole argument for doing this properly rather than subtracting two
     * closes.
     *
     * <p>The index those returns compound into is
     * {@code 1 → 1.10 → 1.155 → 1.0395 → 1.06029 → 1.1027016}, so the deepest peak-to-trough
     * decline is {@code 1.0395 / 1.155 − 1 = −0.10} — from 01-03 to 01-04.
     */
    @Test
    void shouldChainLinkSubPeriodReturnsAcrossAMidPeriodDeposit() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"),
                point("2026-01-03", "2155.0000"),
                point("2026-01-04", "1939.5000"),
                point("2026-01-05", "1978.2900"),
                point("2026-01-06", "2057.4216"));
        givenSeries(points, "1000.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(transfer(TransactionType.DEPOSIT, "1000.00", "2026-01-03")));

        AnalyticsResult result = analyse(points);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.2702");
        assertThat(result.simpleReturnPct()).isEqualByComparingTo("105.7422");
        assertThat(result.netContributions()).isEqualByComparingTo("1000.0000");
        assertThat(result.days()).isEqualTo(5L);

        assertThat(result.maxDrawdown().declinePct()).isEqualByComparingTo("-10.0000");
        assertThat(result.maxDrawdown().peakDate()).isEqualTo(LocalDate.parse("2026-01-03"));
        assertThat(result.maxDrawdown().troughDate()).isEqualTo(LocalDate.parse("2026-01-04"));

        assertThat(result.bestDay().date()).isEqualTo(LocalDate.parse("2026-01-02"));
        assertThat(result.bestDay().returnPct()).isEqualByComparingTo("10.0000");
        assertThat(result.bestDay().absoluteChange()).isEqualByComparingTo("100.0000");

        assertThat(result.worstDay().date()).isEqualTo(LocalDate.parse("2026-01-04"));
        assertThat(result.worstDay().returnPct()).isEqualByComparingTo("-10.0000");
        assertThat(result.worstDay().absoluteChange()).isEqualByComparingTo("-215.5000");
    }

    // ---------------------------------------------------------------- cash flows

    /** A deposit on day one is already inside the opening value, so it must produce no return of
     * its own — the window's TWR is the 10% the market moved and nothing else. */
    @Test
    void shouldNotCreditADepositOnDayOneAsReturn() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"));
        givenSeries(points, "1000.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(transfer(TransactionType.DEPOSIT, "1000.00", "2026-01-01")));

        AnalyticsResult result = analyse(points);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(result.netContributions()).isEqualByComparingTo("1000.0000");
    }

    /**
     * The withdrawal half of the same rule, and the reason {@link Drawdown} is measured on the
     * return index: halving the portfolio by taking money out is not a 50% loss and must not be
     * reported as one.
     */
    @Test
    void shouldMeasureDrawdownOnTheReturnIndexNotOnRawValue() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "500.0000"));
        givenSeries(points, "-500.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(transfer(TransactionType.WITHDRAWAL, "500.00", "2026-01-02")));

        AnalyticsResult result = analyse(points);

        // (500 − (−500)) / 1000 − 1 = 0: the market did nothing, the user took money out.
        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(result.maxDrawdown().declinePct()).isEqualByComparingTo("0.0000");
        assertThat(result.maxDrawdown().peakDate()).isNull();
        assertThat(result.maxDrawdown().troughDate()).isNull();
        // ...while the naive figure still reads as a catastrophe, which is the point.
        assertThat(result.simpleReturnPct()).isEqualByComparingTo("-50.0000");
    }

    /** A BUY moves money between cash and a position; it is not the user putting money in, so it
     * must not be netted out of the day's return. */
    @Test
    void shouldTreatOnlyDepositsAndWithdrawalsAsExternalFlows() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"));
        givenSeries(points, "0.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                new Txn(2L, PORTFOLIO_ID, 100L, TransactionType.BUY, new BigDecimal("10"),
                        new BigDecimal("50.00"), BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-02T00:00:00Z"), null, null),
                new Txn(3L, PORTFOLIO_ID, 100L, TransactionType.DIVIDEND, new BigDecimal("10"),
                        new BigDecimal("2.50"), BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-02T00:00:00Z"), null, null)));

        AnalyticsResult result = analyse(points);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.0000");
    }

    // ---------------------------------------------------------------- edge cases

    /** The brief's named edge case: a single-day period returns 0, not undefined and not a
     * divide-by-zero. There is no elapsed time to annualise, so that one is null. */
    @Test
    void shouldReturnZeroForASingleDayPeriod() {
        List<PerformancePoint> points = List.of(point("2026-01-01", "1000.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(result.simpleReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(result.days()).isZero();
        assertThat(result.annualisedReturnPct()).isNull();
        assertThat(result.bestDay()).isNull();
        assertThat(result.worstDay()).isNull();
        assertThat(result.volatilityPct()).isNull();
        assertThat(result.maxDrawdown().declinePct()).isEqualByComparingTo("0.0000");
    }

    /**
     * A portfolio that went to zero and came back. Dividing by yesterday's zero close has no
     * answer, so that one link is skipped rather than being infinite — and the days either side
     * of it still compound normally.
     */
    @Test
    void shouldSkipTheLinkWhenThePreviousDayClosedAtZero() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "0.0000"),
                point("2026-01-03", "1000.0000"),
                point("2026-01-04", "1100.0000"));
        givenSeries(points, "0.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                transfer(TransactionType.WITHDRAWAL, "1000.00", "2026-01-02"),
                transfer(TransactionType.DEPOSIT, "1000.00", "2026-01-03")));

        AnalyticsResult result = analyse(points);

        // 01-02: (0 − (−1000)) / 1000 − 1 = 0. 01-03: divisor is zero, link skipped.
        // 01-04: 1100 / 1000 − 1 = +0.10. Nothing is Infinity and nothing throws.
        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.0000");
    }

    /** A period with no transactions at all is pure market movement, and the two return measures
     * agree exactly — which is the only case in which they should. */
    @Test
    void shouldAgreeWithTheSimpleReturnWhenThereAreNoCashFlows() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"),
                point("2026-01-03", "1210.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("21.0000");
        assertThat(result.simpleReturnPct()).isEqualByComparingTo("21.0000");
    }

    /** TEST_PLAN.md §4.9 — an empty series is a valid answer with a valid result, never a 500. */
    @Test
    void shouldReturnAnEmptyResultForAPortfolioWithNoPoints() {
        givenSeries(List.of(), "0.0000");

        AnalyticsResult result = service.analyse(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-06"), null);

        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("0.0000");
        assertThat(result.annualisedReturnPct()).isNull();
        assertThat(result.simpleReturnPct()).isNull();
        assertThat(result.netContributions()).isEqualByComparingTo("0.0000");
        assertThat(result.maxDrawdown().declinePct()).isEqualByComparingTo("0.0000");
        assertThat(result.bestDay()).isNull();
        assertThat(result.worstDay()).isNull();
        assertThat(result.volatilityPct()).isNull();
        assertThat(result.days()).isZero();
    }

    /** TEST_PLAN.md §4.2's rule reaches here too: a window that opened at zero has no percentage
     * change, and that is reported as null rather than as Infinity or 0. */
    @Test
    void shouldReturnNullSimpleReturnWhenTheWindowOpenedAtZero() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "0.0000"),
                point("2026-01-02", "1000.0000"));
        givenSeries(points, "1000.0000");
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(transfer(TransactionType.DEPOSIT, "1000.00", "2026-01-02")));

        AnalyticsResult result = analyse(points);

        assertThat(result.simpleReturnPct()).isNull();
        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("0.0000");
    }

    // ---------------------------------------------------------------- annualisation

    /**
     * Over a window of exactly one year the exponent is {@code 365/365 = 1}, so the annualised
     * figure must equal the period figure exactly. A flat year with one 10% day makes that
     * checkable without any compounding arithmetic at all.
     */
    @Test
    void shouldAnnualiseToThePeriodReturnOverExactlyOneYear() {
        List<PerformancePoint> points = new ArrayList<>();
        LocalDate day = LocalDate.parse("2025-01-01");
        for (int i = 0; i < 365; i++) {
            points.add(point(day.toString(), "1000.0000"));
            day = day.plusDays(1);
        }
        points.add(point("2026-01-01", "1100.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.days()).isEqualTo(365L);
        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(result.annualisedReturnPct()).isEqualByComparingTo("10.0000");
    }

    /**
     * A 10% gain over 73 days annualises to {@code 1.1^5 − 1 = 61.051%}, because 73 days goes
     * into a 365-day year exactly five times. An integer exponent keeps the expected value
     * hand-checkable while still exercising the fractional-power path.
     */
    @Test
    void shouldAnnualiseOverTheActualElapsedDayCount() {
        List<PerformancePoint> points = new ArrayList<>();
        LocalDate day = LocalDate.parse("2026-01-01");
        for (int i = 0; i < 73; i++) {
            points.add(point(day.toString(), "1000.0000"));
            day = day.plusDays(1);
        }
        points.add(point(day.toString(), "1100.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.days()).isEqualTo(73L);
        assertThat(result.timeWeightedReturnPct()).isEqualByComparingTo("10.0000");
        assertThat(result.annualisedReturnPct()).isEqualByComparingTo("61.0510");
    }

    // ---------------------------------------------------------------- volatility

    /**
     * Two daily returns of {@code +0.10} and {@code −0.10}: mean 0, sample variance
     * {@code (0.01 + 0.01) / 1 = 0.02}, daily standard deviation {@code √0.02 = 0.1414213562},
     * annualised {@code × √365 = 19.1049731745} → {@code 2.7018512} → <b>270.1851%</b>.
     */
    @Test
    void shouldAnnualiseTheStandardDeviationOfDailyReturns() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"),
                point("2026-01-03", "990.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.volatilityPct()).isEqualByComparingTo("270.1851");
    }

    /** A sample standard deviation of a single observation is undefined, not zero. */
    @Test
    void shouldReturnNullVolatilityForFewerThanTwoDailyReturns() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"));
        givenSeries(points, "0.0000");

        assertThat(analyse(points).volatilityPct()).isNull();
    }

    // ---------------------------------------------------------------- passthrough

    /** Ownership is enforced by delegating to {@link PerformanceService}, which resolves the
     * portfolio through {@code getOrThrow} — this class never queries by id on its own first. */
    @Test
    void shouldReportTheCurrencyAndWindowTheSeriesResolved() {
        List<PerformancePoint> points = List.of(
                point("2026-01-01", "1000.0000"),
                point("2026-01-02", "1100.0000"));
        givenSeries(points, "0.0000");

        AnalyticsResult result = analyse(points);

        assertThat(result.portfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(result.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(result.from()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(result.to()).isEqualTo(LocalDate.parse("2026-01-02"));
    }
}
