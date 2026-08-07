package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.Txn;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.valuation.spi.ConvertedMoney;
import com.protify.portfolio.valuation.spi.FxConversion;
import com.protify.portfolio.valuation.spi.PriceLookup;
import com.protify.portfolio.valuation.spi.PricePoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * day-4-dev-A.md D4-A4. A real {@link PositionPricer} over mocked ports, for the same reason
 * {@code ValuationServiceTest} uses one: pricing is the step being wired, and mocking it would
 * assert nothing.
 *
 * <p>The fixture is the three-currency portfolio the product exists to support — AAPL (USD),
 * RELIANCE (INR) and SHEL (GBP), valued in INR — because {@code by=CURRENCY} on exactly that
 * shape is what demonstrates FX exposure as a percentage.
 */
@ExtendWith(MockitoExtension.class)
class AllocationServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;
    private static final long AAPL_ID = 100L;
    private static final long RELIANCE_ID = 200L;
    private static final long SHEL_ID = 300L;
    private static final long SPY_ID = 400L;
    private static final LocalDate AS_OF = LocalDate.parse("2026-06-01");

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

    private AllocationService service;

    @BeforeEach
    void setUp() {
        service = new AllocationService(portfolioService, transactionRepository,
                new PositionPricer(instrumentRepository, priceLookup, fxConversion), fxConversion);
        lenient().when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(
                new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.INR, Instant.now(), Instant.now()));
    }

    // ---- fixtures ------------------------------------------------------------------------

    private static Instrument instrument(long id, String symbol, String name, AssetType type,
            CurrencyCode currency, String sector) {
        return new Instrument(id, symbol, name, type, currency, "EX", sector, Instant.now());
    }

    private static Txn buy(long instrumentId, String qty, String price, CurrencyCode currency) {
        return new Txn(instrumentId, PORTFOLIO_ID, instrumentId, TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, currency, Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    private static Txn deposit(String amount) {
        return new Txn(999L, PORTFOLIO_ID, null, TransactionType.DEPOSIT, BigDecimal.ZERO,
                new BigDecimal(amount), BigDecimal.ZERO, CurrencyCode.INR,
                Instant.parse("2026-01-01T00:00:00Z"), null, null);
    }

    private void givenInstrument(Instrument instrument, String priceOnAsOf) {
        lenient().when(instrumentRepository.findById(instrument.id())).thenReturn(Optional.of(instrument));
        lenient().when(priceLookup.priceOn(instrument.id(), AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal(priceOnAsOf), instrument.currency(), AS_OF)));
    }

    /** Converts to INR at the given per-currency rates, on any date. */
    private void givenFxToInr(Map<CurrencyCode, String> rates) {
        lenient().when(fxConversion.rate(any(), eq(CurrencyCode.INR), any())).thenAnswer(inv -> {
            CurrencyCode from = inv.getArgument(0);
            return from == CurrencyCode.INR
                    ? Optional.of(BigDecimal.ONE)
                    : Optional.ofNullable(rates.get(from)).map(BigDecimal::new);
        });
        lenient().when(fxConversion.convert(any(), eq(CurrencyCode.INR), any())).thenAnswer(inv -> {
            Money money = inv.getArgument(0);
            LocalDate date = inv.getArgument(2);
            if (money.currency() == CurrencyCode.INR) {
                return Optional.of(new ConvertedMoney(money.amount(), CurrencyCode.INR, date));
            }
            String rate = rates.get(money.currency());
            return rate == null ? Optional.empty()
                    : Optional.of(new ConvertedMoney(money.amount().multiply(new BigDecimal(rate)), CurrencyCode.INR, date));
        });
    }

    /**
     * AAPL 10 @ $150 × 85 = 127,500 INR · RELIANCE 10 @ ₹1,200 = 12,000 INR ·
     * SHEL 40 @ £25 × 110 = 110,000 INR. Total 249,500 INR. Plus 50,000 INR of cash, which must
     * stay out of every slice.
     */
    private void givenThreeCurrencyPortfolio() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                deposit("50000.00"),
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD),
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR),
                buy(SHEL_ID, "40", "20.00", CurrencyCode.GBP)));
        givenInstrument(instrument(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD, "Technology"), "150.00");
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance Industries", AssetType.STOCK, CurrencyCode.INR, "Energy"), "1200.00");
        givenInstrument(instrument(SHEL_ID, "SHEL", "Shell plc", AssetType.ETF, CurrencyCode.GBP, "Energy"), "25.00");
        givenFxToInr(Map.of(CurrencyCode.USD, "85", CurrencyCode.GBP, "110"));
    }

    // ---- the required cases --------------------------------------------------------------

    /** The headline invariant, for every breakdown: weights sum to 100 ± 0.01. They are in fact
     * exact, because the residual is placed rather than dropped. */
    @ParameterizedTest
    @EnumSource(AllocateBy.class)
    void shouldSumWeightsToOneHundredForEveryBreakdown(AllocateBy by) {
        givenThreeCurrencyPortfolio();

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, by, AS_OF, null);

        assertThat(result.slices()).isNotEmpty();
        BigDecimal sum = result.slices().stream()
                .map(AllocationSlice::weightPct)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("100.0000");
    }

    /**
     * {@code by=CURRENCY} on the three-currency portfolio — the breakdown that shows FX exposure
     * as a percentage, and the one D4-A4 singles out as needing to be right. Every figure below
     * is hand-computed from the fixture.
     */
    @Test
    void shouldBreakDownAThreeCurrencyPortfolioByCurrency() {
        givenThreeCurrencyPortfolio();

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, null);

        assertThat(result.currency()).isEqualTo(CurrencyCode.INR);
        assertThat(result.total()).isEqualByComparingTo("249500.0000");
        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("USD", "GBP", "INR");

        AllocationSlice usd = result.slices().get(0);
        assertThat(usd.value()).isEqualByComparingTo("127500.0000");
        assertThat(usd.label()).isEqualTo("US Dollar");
        // 127500/249500 = 51.1022%. These three happen to round to exactly 100 with no residual
        // to place — shouldPutTheRoundingResidualOnTheLargestSlice covers the case that does.
        assertThat(usd.weightPct()).isEqualByComparingTo("51.1022");

        AllocationSlice gbp = result.slices().get(1);
        assertThat(gbp.value()).isEqualByComparingTo("110000.0000");
        assertThat(gbp.weightPct()).isEqualByComparingTo("44.0882");

        AllocationSlice inr = result.slices().get(2);
        assertThat(inr.value()).isEqualByComparingTo("12000.0000");
        assertThat(inr.weightPct()).isEqualByComparingTo("4.8096");
    }

    @Test
    void shouldGroupByAssetTypeWithDisplayableLabels() {
        givenThreeCurrencyPortfolio();

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.ASSET_TYPE, AS_OF, null);

        // AAPL + RELIANCE are STOCK (127500 + 12000); SHEL is an ETF (110000).
        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("STOCK", "ETF");
        assertThat(result.slices().get(0).value()).isEqualByComparingTo("139500.0000");
        assertThat(result.slices().get(0).instrumentCount()).isEqualTo(2);
        assertThat(result.slices().get(1).instrumentCount()).isEqualTo(1);
        assertThat(result.slices().get(0).label()).isEqualTo("Stock");
    }

    @Test
    void shouldGroupBySectorCombiningInstrumentsThatShareOne() {
        givenThreeCurrencyPortfolio();

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.SECTOR, AS_OF, null);

        // RELIANCE + SHEL are both Energy (12000 + 110000); AAPL is Technology (127500).
        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("Technology", "Energy");
        assertThat(result.slices().get(1).value()).isEqualByComparingTo("122000.0000");
        assertThat(result.slices().get(1).instrumentCount()).isEqualTo(2);
    }

    /** An instrument with no sector is reported as unclassified, not dropped — dropping it would
     * silently make the weights add up while hiding part of the portfolio. */
    @Test
    void shouldReportAnInstrumentWithNoSectorAsUnclassified() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(SPY_ID, "10", "400.00", CurrencyCode.INR)));
        givenInstrument(instrument(SPY_ID, "SPY", "S&P 500 ETF", AssetType.ETF, CurrencyCode.INR, null), "500.00");
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.SECTOR, AS_OF, null);

        assertThat(result.slices()).hasSize(1);
        assertThat(result.slices().get(0).key()).isEqualTo("UNCLASSIFIED");
        assertThat(result.slices().get(0).label()).isEqualTo("Unclassified");
        assertThat(result.slices().get(0).weightPct()).isEqualByComparingTo("100.0000");
    }

    /** §4.9: an empty portfolio is empty slices and a zero total — never a divide-by-zero. */
    @Test
    void shouldReturnEmptySlicesForAnEmptyPortfolioWithoutDividingByZero() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, null);

        assertThat(result.slices()).isEmpty();
        assertThat(result.total()).isEqualByComparingTo("0.0000");
        assertThat(result.cashBalance()).isEqualByComparingTo("0.0000");
        assertThat(result.stale()).isFalse();
    }

    @Test
    void shouldReportASingleHoldingAsOneSliceAtOneHundredPercent() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR)));
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance Industries", AssetType.STOCK, CurrencyCode.INR, "Energy"), "1200.00");
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.INSTRUMENT, AS_OF, null);

        assertThat(result.slices()).hasSize(1);
        assertThat(result.slices().get(0).key()).isEqualTo("RELIANCE");
        assertThat(result.slices().get(0).label()).isEqualTo("Reliance Industries");
        assertThat(result.slices().get(0).weightPct()).isEqualByComparingTo("100.0000");
        assertThat(result.slices().get(0).value()).isEqualByComparingTo("12000.0000");
    }

    /**
     * Cash is reported but never sliced. The 50,000 INR deposit in the fixture is spent down by
     * the three BUYs, so the balance is negative here — that is the correct arithmetic, and the
     * assertion that matters is that no wedge of the pie is cash and the total is market value
     * alone.
     */
    @Test
    void shouldExcludeCashFromSlicesAndReportItSeparately() {
        givenThreeCurrencyPortfolio();

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.ASSET_TYPE, AS_OF, null);

        assertThat(result.slices()).extracting(AllocationSlice::key).doesNotContain("CASH");
        // 50,000 deposited, less 85,000 (AAPL) + 10,000 (RELIANCE) + 88,000 (SHEL) at trade-date FX.
        assertThat(result.cashBalance()).isEqualByComparingTo("-133000.0000");
        BigDecimal sliceSum = result.slices().stream()
                .map(AllocationSlice::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(result.total()).isEqualByComparingTo(sliceSum);
        assertThat(result.total()).isEqualByComparingTo("249500.0000");
    }

    /**
     * Three equal holdings cannot be weighted 33.3333 each — that sums to 99.9999. The residual
     * goes on the largest slice; with a genuine tie the first in the deterministic ordering takes
     * it, so the total is exactly 100 either way.
     */
    @Test
    void shouldPutTheRoundingResidualOnTheLargestSlice() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "1", "100.00", CurrencyCode.INR),
                buy(RELIANCE_ID, "1", "100.00", CurrencyCode.INR),
                buy(SHEL_ID, "1", "100.00", CurrencyCode.INR)));
        givenInstrument(instrument(AAPL_ID, "AAPL", "Apple", AssetType.STOCK, CurrencyCode.INR, "Technology"), "100.00");
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance", AssetType.STOCK, CurrencyCode.INR, "Energy"), "100.00");
        givenInstrument(instrument(SHEL_ID, "SHEL", "Shell", AssetType.STOCK, CurrencyCode.INR, "Utilities"), "100.00");
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.INSTRUMENT, AS_OF, null);

        assertThat(result.slices()).hasSize(3);
        assertThat(result.slices()).extracting(AllocationSlice::weightPct)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("33.3334"),
                        new BigDecimal("33.3333"), new BigDecimal("33.3333"));
        BigDecimal sum = result.slices().stream()
                .map(AllocationSlice::weightPct).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("100.0000");
    }

    /** One unpriceable holding flags the result stale; it does not blank the other slices or
     * throw (§4.3). Its weight simply is not counted, so the rest still sum to 100. */
    @Test
    void shouldFlagStaleAndKeepTheOtherSlicesWhenOneHoldingCannotBePriced() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR),
                buy(SPY_ID, "5", "400.00", CurrencyCode.INR)));
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance", AssetType.STOCK, CurrencyCode.INR, "Energy"), "1200.00");
        lenient().when(instrumentRepository.findById(SPY_ID)).thenReturn(
                Optional.of(instrument(SPY_ID, "SPY", "S&P 500 ETF", AssetType.ETF, CurrencyCode.INR, "Broad")));
        when(priceLookup.priceOn(SPY_ID, AS_OF)).thenReturn(Optional.empty());
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.INSTRUMENT, AS_OF, null);

        assertThat(result.stale()).isTrue();
        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("RELIANCE");
        assertThat(result.slices().get(0).weightPct()).isEqualByComparingTo("100.0000");
    }

    /** A position sold down to zero is not a slice — and is never even priced. */
    @Test
    void shouldExcludeAFullySoldPositionFromTheBreakdown() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR),
                new Txn(2L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("5"),
                        new BigDecimal("100.00"), BigDecimal.ZERO, CurrencyCode.INR,
                        Instant.parse("2026-01-01T00:00:00Z"), null, null),
                new Txn(3L, PORTFOLIO_ID, AAPL_ID, TransactionType.SELL, new BigDecimal("5"),
                        new BigDecimal("120.00"), BigDecimal.ZERO, CurrencyCode.INR,
                        Instant.parse("2026-02-01T00:00:00Z"), null, null)));
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance", AssetType.STOCK, CurrencyCode.INR, "Energy"), "1200.00");
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.INSTRUMENT, AS_OF, null);

        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("RELIANCE");
        verify(priceLookup, never()).priceOn(eq(AAPL_ID), any());
    }

    /** Transactions after {@code asOf} are not in the picture — allocation is as at a date. */
    @Test
    void shouldIgnoreTransactionsExecutedAfterAsOf() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR),
                new Txn(2L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("5"),
                        new BigDecimal("100.00"), BigDecimal.ZERO, CurrencyCode.INR,
                        Instant.parse("2026-09-01T00:00:00Z"), null, null)));
        givenInstrument(instrument(RELIANCE_ID, "RELIANCE", "Reliance", AssetType.STOCK, CurrencyCode.INR, "Energy"), "1200.00");
        givenFxToInr(Map.of());

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.INSTRUMENT, AS_OF, null);

        assertThat(result.slices()).extracting(AllocationSlice::key).containsExactly("RELIANCE");
    }

    /** The requested currency overrides the portfolio's base without touching a stored row. */
    @Test
    void shouldHonourAnExplicitCurrencyOverride() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID))
                .thenReturn(List.of(buy(AAPL_ID, "10", "100.00", CurrencyCode.USD)));
        givenInstrument(instrument(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD, "Technology"), "150.00");
        lenient().when(fxConversion.rate(any(), eq(CurrencyCode.USD), any())).thenReturn(Optional.of(BigDecimal.ONE));
        lenient().when(fxConversion.convert(any(), eq(CurrencyCode.USD), any())).thenAnswer(inv -> {
            Money money = inv.getArgument(0);
            return Optional.of(new ConvertedMoney(money.amount(), CurrencyCode.USD, inv.getArgument(2)));
        });

        AllocationResult result = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, CurrencyCode.USD);

        assertThat(result.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(result.total()).isEqualByComparingTo("1500.0000");
    }

    /** §4.7: another user's portfolio is a 404 from the service's own lookup, never a 403 and
     * never a partially-computed answer. */
    @Test
    void shouldPropagateNotFoundForAnotherUsersPortfolio() {
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID))
                .thenThrow(new NotFoundException("portfolio", PORTFOLIO_ID));

        assertThatThrownBy(() -> service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, null))
                .isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).findByPortfolioOrderByExecutedAt(anyLong());
    }

    /** The slices are the same money the valuation reports, because both run the same
     * {@link PositionPricer} over the same fold. A drift between them would be invisible in
     * either class alone, so it is asserted across the two. */
    @Test
    void shouldReportATotalThatMatchesTheValuationMarketValue() {
        givenThreeCurrencyPortfolio();
        ValuationService valuationService = new ValuationService(portfolioService, transactionRepository,
                new PositionPricer(instrumentRepository, priceLookup, fxConversion), fxConversion);

        AllocationResult allocation = service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, null);
        ValuationResult valuation = valuationService.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(allocation.total()).isEqualByComparingTo(valuation.marketValue());
        assertThat(allocation.cashBalance()).isEqualByComparingTo(valuation.cashBalance());
    }

    /** Weight arithmetic must never introduce a double. Belt-and-braces alongside the ArchUnit
     * rule, on the one class where a percentage makes {@code double} tempting. */
    @Test
    void shouldExposeEveryWeightAsBigDecimal() {
        givenThreeCurrencyPortfolio();
        Map<String, BigDecimal> byKey = new HashMap<>();

        service.allocate(USER_ID, PORTFOLIO_ID, AllocateBy.CURRENCY, AS_OF, null).slices()
                .forEach(slice -> byKey.put(slice.key(), slice.weightPct()));

        assertThat(byKey.values()).allSatisfy(w -> assertThat(w.scale()).isEqualTo(4));
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
