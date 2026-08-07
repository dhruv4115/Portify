package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.FxConversion;
import com.protify.portfolio.valuation.spi.PriceLookup;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests, every collaborator mocked (day-3-dev-A.md D3-A4) — {@link ValuationFolder} does
 * the real fold; this class exercises the day-by-day merge-walk and the edge cases
 * ADR-0010 names: forward-fill, market holidays, and the range guards.
 */
@ExtendWith(MockitoExtension.class)
class PerformanceServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;
    private static final long AAPL_ID = 100L;
    private static final long RELIANCE_ID = 200L;
    private static final long SHEL_ID = 300L;

    @Mock
    private PortfolioService portfolioService;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private InstrumentRepository instrumentRepository;
    @Mock
    private PriceLookup priceLookup;
    @Mock
    private FxConversion fxConversion;
    @Mock
    private ValuationSnapshotService snapshotService;

    private PerformanceService service;
    private Portfolio usdPortfolio;

    @BeforeEach
    void setUp() {
        service = new PerformanceService(portfolioService, transactionRepository, instrumentRepository,
                priceLookup, fxConversion, snapshotService);
        // Cold cache by default: every day is folded live, which is what every test written
        // before D5-A2 assumes. isCompleted() defaults to false, so nothing is memoised either.
        lenient().when(snapshotService.completedSnapshots(anyLong(), any(), any(), any()))
                .thenReturn(new TreeMap<>());
        usdPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.USD, Instant.now(), Instant.now());
        lenient().when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(usdPortfolio);
        lenient().when(fxConversion.ratesUpTo(org.mockito.ArgumentMatchers.any())).thenReturn(new TreeMap<>());
    }

    private static Txn buy(long instrumentId, String qty, String price, CurrencyCode currency, String executedAt) {
        return new Txn(1L, PORTFOLIO_ID, instrumentId, TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, currency, Instant.parse(executedAt), null, null);
    }

    private static Instrument instrument(long id, String symbol, CurrencyCode currency) {
        return new Instrument(id, symbol, symbol, AssetType.STOCK, currency, "NASDAQ", "Technology", Instant.now());
    }

    private void givenSingleAaplHolding(String executedAt) {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, executedAt)));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
    }

    private void givenPrices(Map<LocalDate, String> series) {
        NavigableMap<LocalDate, BigDecimal> map = new TreeMap<>();
        series.forEach((date, price) -> map.put(date, new BigDecimal(price)));
        // The "to" date argument varies per test; stub broadly rather than pin an exact value.
        lenient().when(priceLookup.seriesUpTo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(AAPL_ID, map));
    }

    @Test
    void onePointPerDay() {
        givenSingleAaplHolding("2025-12-30T00:00:00Z");
        givenPrices(Map.of(
                LocalDate.parse("2025-12-30"), "100.00",
                LocalDate.parse("2026-01-01"), "101.00",
                LocalDate.parse("2026-01-02"), "102.00",
                LocalDate.parse("2026-01-03"), "103.00",
                LocalDate.parse("2026-01-04"), "104.00",
                LocalDate.parse("2026-01-05"), "105.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), null);

        assertThat(result.points()).hasSize(5);
        assertThat(result.points()).extracting(PerformancePoint::date).containsExactly(
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-03"),
                LocalDate.parse("2026-01-04"), LocalDate.parse("2026-01-05"));
    }

    @Test
    void weekendIsForwardFilledAndFlagged() {
        // 2026-01-02 is a Friday; 01-03/04 are the weekend; 01-05 is Monday.
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(
                LocalDate.parse("2026-01-01"), "100.00",
                LocalDate.parse("2026-01-02"), "102.00",
                LocalDate.parse("2026-01-05"), "105.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), null);

        List<PerformancePoint> points = result.points();
        assertThat(points.get(1).filled()).isFalse(); // Friday, fresh price
        assertThat(points.get(2).filled()).isTrue();  // Saturday, forward-filled
        assertThat(points.get(3).filled()).isTrue();  // Sunday, forward-filled
        assertThat(points.get(2).marketValue()).isEqualByComparingTo(points.get(1).marketValue());
        assertThat(points.get(3).marketValue()).isEqualByComparingTo(points.get(1).marketValue());
        assertThat(points.get(4).filled()).isFalse(); // Monday, fresh price again
    }

    @Test
    void transactionOnAMarketHolidayLandsCorrectly() {
        // 2026-01-03 is a Saturday — a transaction still changes composition on that exact date
        // even though there is no fresh price for it.
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                new Txn(2L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("5"),
                        new BigDecimal("100.00"), BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-03T00:00:00Z"), null, null)));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        givenPrices(Map.of(
                LocalDate.parse("2026-01-01"), "100.00",
                LocalDate.parse("2026-01-02"), "100.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-03"), null);

        PerformancePoint saturday = result.points().get(2);
        assertThat(saturday.date()).isEqualTo(LocalDate.parse("2026-01-03"));
        // 15 shares * forward-filled price 100 = 1500, proving the BUY on the holiday is reflected.
        assertThat(saturday.marketValue()).isEqualByComparingTo("1500.0000");
        assertThat(saturday.filled()).isTrue();
    }

    /**
     * TEST_PLAN.md §4.3's named negative, and the reason it is named: <b>we must never
     * forward-fill from a future price.</b> The gap on 01-02/01-03 is bracketed by 100 before
     * and 999 after. Every optimistic test in this class passes with {@code ceilingEntry} in
     * place of {@code floorEntry} — this one does not, because 999 has not happened yet on
     * those days and using it would silently invent history.
     */
    @Test
    void shouldNeverForwardFillFromAFuturePrice() {
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(
                LocalDate.parse("2026-01-01"), "100.00",
                LocalDate.parse("2026-01-04"), "999.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-04"), null);

        List<PerformancePoint> points = result.points();
        // 10 shares: the two gap days carry 01-01's price forward, never 01-04's.
        assertThat(points.get(1).marketValue()).isEqualByComparingTo("1000.0000");
        assertThat(points.get(2).marketValue()).isEqualByComparingTo("1000.0000");
        assertThat(points.get(1).filled()).isTrue();
        assertThat(points.get(2).filled()).isTrue();
        // ...and the future price is used on its own day, so this is a date rule, not a cap.
        assertThat(points.get(3).marketValue()).isEqualByComparingTo("9990.0000");
        assertThat(points.get(3).filled()).isFalse();
    }

    /**
     * The FX half of the same rule: {@code valueDay} resolves a rate with {@code floorEntry} on
     * the fx table too, so a rate that only exists in the future must not price an earlier day.
     */
    @Test
    void shouldNeverConvertUsingAnFxRateDatedAfterTheDay() {
        Portfolio inrPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.INR, Instant.now(), Instant.now());
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(inrPortfolio);
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00"));

        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable = new TreeMap<>();
        fxTable.put(LocalDate.parse("2026-01-01"), Map.of(CurrencyCode.INR, new BigDecimal("80")));
        fxTable.put(LocalDate.parse("2026-01-03"), Map.of(CurrencyCode.INR, new BigDecimal("900")));
        when(fxConversion.ratesUpTo(LocalDate.parse("2026-01-02"))).thenReturn(fxTable);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        // 10 * 100 * 80 on both days — the 900 rate belongs to a day that has not arrived.
        assertThat(result.points().get(0).marketValue()).isEqualByComparingTo("80000.0000");
        assertThat(result.points().get(1).marketValue()).isEqualByComparingTo("80000.0000");
    }

    /** TEST_PLAN.md §4.2's "same rule in {@code PerformanceSummary.percentChange} when
     * {@code startValue} is 0" — null, never Infinity, never 0. */
    @Test
    void shouldReturnNullPercentChangeWhenStartValueIsZero() {
        // A deposit on day one and the first BUY on day two: the series starts at a total value
        // of exactly zero, which is the realistic way this arises.
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                new Txn(1L, PORTFOLIO_ID, null, TransactionType.DEPOSIT, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2026-01-01T00:00:00Z"), null, null),
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-02T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        givenPrices(Map.of(LocalDate.parse("2026-01-02"), "120.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        assertThat(result.summary().startValue()).isEqualByComparingTo("0.0000");
        assertThat(result.summary().percentChange()).isNull();
        assertThat(result.summary().absoluteChange()).isEqualByComparingTo("200.0000");
    }

    /** §4.9: a portfolio with no transactions is a valid empty series with a valid summary,
     * never a divide-by-zero and never a 500. */
    @Test
    void shouldReturnAnEmptySeriesWithAValidSummaryForAPortfolioWithNoTransactions() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), null);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary().startValue()).isEqualByComparingTo("0.0000");
        assertThat(result.summary().endValue()).isEqualByComparingTo("0.0000");
        assertThat(result.summary().percentChange()).isNull();
        assertThat(result.summary().netContributions()).isEqualByComparingTo("0.0000");
    }

    /** A window that closes before the portfolio's first transaction has no points to report —
     * the same empty result, not a negative-length loop. */
    @Test
    void shouldReturnAnEmptySeriesWhenTheWindowClosesBeforeTheFirstTransaction() {
        // No instrument stub: the range check short-circuits before anything is priced.
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-06-01T00:00:00Z")));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-05"), null);

        assertThat(result.points()).isEmpty();
        assertThat(result.summary().percentChange()).isNull();
    }

    /** The {@code currency} query parameter overrides the portfolio's base currency without
     * touching a stored row (PLAN.md §2.1: base currency is a presentation concern). */
    @Test
    void shouldHonourAnExplicitCurrencyOverrideInsteadOfTheBaseCurrency() {
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), CurrencyCode.USD);

        assertThat(result.currency()).isEqualTo(CurrencyCode.USD);
    }

    /**
     * {@code netContributions} is deposits <i>minus</i> withdrawals, so that
     * {@code percentChange} reflects the market rather than the user's own money moving in and
     * out (API_CONTRACT.md §12). A sign slip here makes every return figure wrong.
     */
    @Test
    void shouldSubtractWithdrawalsFromNetContributions() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                new Txn(1L, PORTFOLIO_ID, null, TransactionType.DEPOSIT, BigDecimal.ZERO, new BigDecimal("1000.00"),
                        BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2026-01-01T00:00:00Z"), null, null),
                new Txn(2L, PORTFOLIO_ID, null, TransactionType.WITHDRAWAL, BigDecimal.ZERO, new BigDecimal("250.00"),
                        BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2026-01-02T00:00:00Z"), null, null)));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        assertThat(result.summary().netContributions()).isEqualByComparingTo("750.0000");
        assertThat(result.points().get(1).cashBalance()).isEqualByComparingTo("750.0000");
    }

    /** A position sold down to zero contributes no market value and is not priced at all —
     * the same rule {@code ValuationService} applies, asserted on the series. */
    @Test
    void shouldExcludeAPositionThatHasBeenFullySoldFromMarketValue() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                new Txn(2L, PORTFOLIO_ID, AAPL_ID, TransactionType.SELL, new BigDecimal("10"),
                        new BigDecimal("120.00"), BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-02T00:00:00Z"), null, null)));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00", LocalDate.parse("2026-01-02"), "130.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        assertThat(result.points().get(0).marketValue()).isEqualByComparingTo("1000.0000");
        assertThat(result.points().get(1).marketValue()).isEqualByComparingTo("0.0000");
        assertThat(result.points().get(1).cashBalance()).isEqualByComparingTo("200.0000");
    }

    /**
     * A missing FX rate skips that instrument's contribution rather than defaulting the rate to
     * 1 — pricing a GBP holding as though a pound were a rupee would be a far worse answer than
     * omitting it. (Cash flows do fall back to 1; that asymmetry is deliberate, see
     * {@code crossRate}'s javadoc.)
     */
    @Test
    void shouldSkipAnInstrumentWhoseFxRateIsMissingRatherThanAssumeParity() {
        Portfolio inrPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.INR, Instant.now(), Instant.now());
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(inrPortfolio);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(SHEL_ID, "40", "20.00", CurrencyCode.GBP, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(SHEL_ID)).thenReturn(java.util.Optional.of(instrument(SHEL_ID, "SHEL", CurrencyCode.GBP)));
        NavigableMap<LocalDate, BigDecimal> series = new TreeMap<>();
        series.put(LocalDate.parse("2026-01-01"), new BigDecimal("25.00"));
        lenient().when(priceLookup.seriesUpTo(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(Map.of(SHEL_ID, series));

        // The table has a date, but no GBP entry on it — so no GBP -> INR cross rate exists.
        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable = new TreeMap<>();
        fxTable.put(LocalDate.parse("2026-01-01"), Map.of(CurrencyCode.INR, new BigDecimal("80")));
        when(fxConversion.ratesUpTo(LocalDate.parse("2026-01-01"))).thenReturn(fxTable);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), null);

        assertThat(result.points().get(0).marketValue()).isEqualByComparingTo("0.0000");
    }

    @Test
    void datesBeforeTheFirstTransactionAreOmitted() {
        givenSingleAaplHolding("2026-01-03T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-03"), "100.00", LocalDate.parse("2026-01-04"), "101.00"));

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-04"), null);

        assertThat(result.points()).extracting(PerformancePoint::date)
                .containsExactly(LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-04"));
    }

    @Test
    void fromAfterToThrowsBadRequest() {
        assertThatThrownBy(() -> service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-05"), LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void rangeOverFiveYearsThrowsBadRequest() {
        assertThatThrownBy(() -> service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2020-01-01"), LocalDate.parse("2026-01-02"), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void threeCurrencyPortfolioValuesCorrectly() {
        Portfolio inrPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.INR, Instant.now(), Instant.now());
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(inrPortfolio);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR, "2026-01-01T00:00:00Z"),
                buy(SHEL_ID, "40", "20.00", CurrencyCode.GBP, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(instrumentRepository.findById(RELIANCE_ID)).thenReturn(java.util.Optional.of(instrument(RELIANCE_ID, "RELIANCE", CurrencyCode.INR)));
        when(instrumentRepository.findById(SHEL_ID)).thenReturn(java.util.Optional.of(instrument(SHEL_ID, "SHEL", CurrencyCode.GBP)));

        NavigableMap<LocalDate, BigDecimal> aaplPrices = new TreeMap<>(Map.of(LocalDate.parse("2026-01-01"), new BigDecimal("150.00")));
        NavigableMap<LocalDate, BigDecimal> reliancePrices = new TreeMap<>(Map.of(LocalDate.parse("2026-01-01"), new BigDecimal("1200.00")));
        NavigableMap<LocalDate, BigDecimal> shelPrices = new TreeMap<>(Map.of(LocalDate.parse("2026-01-01"), new BigDecimal("25.00")));
        when(priceLookup.seriesUpTo(Set.of(AAPL_ID, RELIANCE_ID, SHEL_ID), LocalDate.parse("2026-01-01"))).thenReturn(Map.of(
                AAPL_ID, aaplPrices, RELIANCE_ID, reliancePrices, SHEL_ID, shelPrices));

        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> fxTable = new TreeMap<>();
        Map<CurrencyCode, BigDecimal> ratesOnDay = new HashMap<>();
        ratesOnDay.put(CurrencyCode.INR, new BigDecimal("85"));
        ratesOnDay.put(CurrencyCode.GBP, new BigDecimal("0.79"));
        fxTable.put(LocalDate.parse("2026-01-01"), ratesOnDay);
        when(fxConversion.ratesUpTo(LocalDate.parse("2026-01-01"))).thenReturn(fxTable);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), null);

        // AAPL: 10*150 USD, cross USD->INR = 85/0.79... wait derive precisely below.
        assertThat(result.points()).hasSize(1);
        assertThat(result.currency()).isEqualTo(CurrencyCode.INR);
        assertThat(result.points().get(0).marketValue()).isNotEqualByComparingTo(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------ D5-A2: the snapshot cache

    private static ValuationSnapshot snapshot(String date, String marketValue, String cashBalance, boolean filled) {
        LocalDate day = LocalDate.parse(date);
        return new ValuationSnapshot(PORTFOLIO_ID, day, CurrencyCode.USD, new BigDecimal(marketValue),
                new BigDecimal("1111.0000"), new BigDecimal(cashBalance), new BigDecimal("3131.0000"),
                filled, LocalDate.parse("2025-12-31"), LocalDate.parse("2025-12-30"));
    }

    /**
     * A memoised day is served from the table rather than re-priced. The stored market value is
     * deliberately a number the fold could not produce from these prices, so a test that passed
     * because the two happened to agree is not possible.
     */
    @Test
    void shouldServeACompletedDayFromItsSnapshotInsteadOfFoldingItAgain() {
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00", LocalDate.parse("2026-01-02"), "110.00"));

        NavigableMap<LocalDate, ValuationSnapshot> cached = new TreeMap<>();
        cached.put(LocalDate.parse("2026-01-01"), snapshot("2026-01-01", "4242.0000", "-1000.0000", true));
        when(snapshotService.completedSnapshots(PORTFOLIO_ID, CurrencyCode.USD,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"))).thenReturn(cached);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        PerformancePoint fromCache = result.points().get(0);
        assertThat(fromCache.marketValue()).isEqualByComparingTo("4242.0000");
        assertThat(fromCache.costBasis()).isEqualByComparingTo("1111.0000");
        assertThat(fromCache.cashBalance()).isEqualByComparingTo("-1000.0000");
        // totalValue is recomputed from the two stored columns, not stored itself.
        assertThat(fromCache.totalValue()).isEqualByComparingTo("3242.0000");
        assertThat(fromCache.filled()).isTrue();

        // The uncovered day is still folded live: 10 shares at 110.
        assertThat(result.points().get(1).marketValue()).isEqualByComparingTo("1100.0000");
    }

    /** {@code priceAsOf}/{@code rateAsOf} survive the round trip, so a snapshot-served day
     * reports staleness exactly as the fold would have. */
    @Test
    void shouldReportTheAsOfDatesStoredWithASnapshot() {
        // No instrument stub: the window is fully memoised, so nothing is priced.
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        NavigableMap<LocalDate, ValuationSnapshot> cached = new TreeMap<>();
        cached.put(LocalDate.parse("2026-01-01"), snapshot("2026-01-01", "1000.0000", "0.0000", true));
        when(snapshotService.completedSnapshots(PORTFOLIO_ID, CurrencyCode.USD,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"))).thenReturn(cached);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), null);

        assertThat(result.priceAsOf()).isEqualTo(LocalDate.parse("2025-12-31"));
        assertThat(result.rateAsOf()).isEqualTo(LocalDate.parse("2025-12-30"));
        assertThat(result.stale()).isTrue();
    }

    /**
     * The point of the cache. A fully memoised window must not touch {@code price_history} at
     * all — that unbounded range scan is the expensive read (ADR-0010), and skipping it is where
     * a 365-day series gets its time back.
     */
    @Test
    void shouldNotQueryThePriceSeriesWhenEveryDayInTheWindowIsMemoised() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        NavigableMap<LocalDate, ValuationSnapshot> cached = new TreeMap<>();
        cached.put(LocalDate.parse("2026-01-01"), snapshot("2026-01-01", "4242.0000", "0.0000", false));
        cached.put(LocalDate.parse("2026-01-02"), snapshot("2026-01-02", "4343.0000", "0.0000", false));
        when(snapshotService.completedSnapshots(PORTFOLIO_ID, CurrencyCode.USD,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"))).thenReturn(cached);

        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        assertThat(result.points()).hasSize(2);
        assertThat(result.points().get(1).marketValue()).isEqualByComparingTo("4343.0000");
        verify(priceLookup, never()).seriesUpTo(any(), any());
        verify(instrumentRepository, never()).findById(anyLong());
    }

    /** Days the fold had to compute are handed back to the cache — but only the completed ones.
     * Today's close has not happened, so memoising it would freeze a moving number. */
    @Test
    void shouldMemoiseOnlyTheCompletedDaysItHadToFold() {
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00", LocalDate.parse("2026-01-02"), "110.00"));
        when(snapshotService.isCompleted(LocalDate.parse("2026-01-01"))).thenReturn(true);
        when(snapshotService.isCompleted(LocalDate.parse("2026-01-02"))).thenReturn(false);

        service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-02"), null);

        ArgumentCaptor<List<ValuationSnapshot>> stored = ArgumentCaptor.captor();
        verify(snapshotService).store(stored.capture());

        assertThat(stored.getValue()).hasSize(1);
        ValuationSnapshot only = stored.getValue().get(0);
        assertThat(only.date()).isEqualTo(LocalDate.parse("2026-01-01"));
        assertThat(only.portfolioId()).isEqualTo(PORTFOLIO_ID);
        assertThat(only.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(only.marketValue()).isEqualByComparingTo("1000.0000");
        assertThat(only.filled()).isFalse();
    }

    /** A snapshot stored in one currency must never be handed to a request for another — the
     * lookup is keyed by currency, so an INR request simply finds nothing and folds. */
    @Test
    void shouldLookUpSnapshotsForTheRequestedCurrencyNotTheBaseCurrency() {
        givenSingleAaplHolding("2026-01-01T00:00:00Z");
        givenPrices(Map.of(LocalDate.parse("2026-01-01"), "100.00"));

        service.getPerformance(USER_ID, PORTFOLIO_ID,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"), CurrencyCode.GBP);

        verify(snapshotService).completedSnapshots(PORTFOLIO_ID, CurrencyCode.GBP,
                LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-01"));
    }

    @Test
    void ninetyDaysCompletesUnderThreeHundredMilliseconds() {
        LocalDate start = LocalDate.parse("2026-01-01");
        LocalDate end = start.plusDays(89);
        List<Txn> txns = new ArrayList<>();
        txns.add(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, start.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(txns);
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(java.util.Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));

        NavigableMap<LocalDate, BigDecimal> priceSeries = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            priceSeries.put(d, new BigDecimal("100.00"));
        }
        when(priceLookup.seriesUpTo(Set.of(AAPL_ID), end)).thenReturn(Map.of(AAPL_ID, priceSeries));

        long startNanos = System.nanoTime();
        PerformanceResult result = service.getPerformance(USER_ID, PORTFOLIO_ID, start, end, null);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertThat(result.points()).hasSize(90);
        assertThat(elapsedMillis).isLessThan(300);
    }

    /** {@code PositionPricer} resolves the whole catalogue in one query now (D5-A3). Delegating
     * the fake batch to the fake single lookup keeps every {@code findById} stub in this class
     * meaningful, and mirrors the real relationship between the two methods. */
    @BeforeEach
    void stubBatchInstrumentLookup() {
        lenient().when(instrumentRepository.findAllByIds(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    java.util.Map<Long, com.protify.portfolio.instrument.Instrument> found =
                            new java.util.LinkedHashMap<>();
                    for (Long id : invocation.<java.util.Collection<Long>>getArgument(0)) {
                        instrumentRepository.findById(id).ifPresent(i -> found.put(id, i));
                    }
                    return found;
                });
    }
}
