package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.error.ValidationException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests, every collaborator mocked (day-3-dev-A.md D3-A3) — {@link ValuationFolder} does
 * the real maths and is exercised transitively; this class asserts the service wires it to
 * Dev B's pricing/FX services correctly and handles the edge cases TEST_PLAN.md §4.2/§4.3 name.
 */
@ExtendWith(MockitoExtension.class)
class ValuationServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;
    private static final long AAPL_ID = 100L;
    private static final long RELIANCE_ID = 200L;
    private static final long SHEL_ID = 300L;
    private static final LocalDate TRADE_DATE = LocalDate.parse("2026-01-01");
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

    private ValuationService service;
    private Portfolio usdPortfolio;

    @BeforeEach
    void setUp() {
        // A real PositionPricer over the same mocks, not a mock of it: the pricer is the step
        // whose wiring these tests are about, and stubbing it would assert nothing.
        service = new ValuationService(portfolioService, transactionRepository,
                new PositionPricer(instrumentRepository, priceLookup, fxConversion), fxConversion);
        usdPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.USD, Instant.now(), Instant.now());
        lenient().when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(usdPortfolio);
    }

    private static Txn buy(long instrumentId, String qty, String price, CurrencyCode currency, String executedAt) {
        return new Txn(null, PORTFOLIO_ID, instrumentId, TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, currency, Instant.parse(executedAt), null, null);
    }

    private static Txn sell(long instrumentId, String qty, String price, CurrencyCode currency, String executedAt) {
        return new Txn(null, PORTFOLIO_ID, instrumentId, TransactionType.SELL, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, currency, Instant.parse(executedAt), null, null);
    }

    private static Txn deposit(String amount, String executedAt) {
        return new Txn(null, PORTFOLIO_ID, null, TransactionType.DEPOSIT, BigDecimal.ZERO,
                new BigDecimal(amount), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(executedAt), null, null);
    }

    private static Instrument instrument(long id, String symbol, CurrencyCode currency) {
        return new Instrument(id, symbol, symbol, AssetType.STOCK, currency, "NASDAQ", "Technology", Instant.now());
    }

    /** Identity FX: every {@code rate}/{@code convert} call against {@code target} resolves to 1
     * unless overridden — enough for single-currency scenarios. */
    private void stubIdentityFx(CurrencyCode target) {
        lenient().when(fxConversion.rate(any(), eq(target), any())).thenReturn(Optional.of(BigDecimal.ONE));
        lenient().when(fxConversion.convert(any(), eq(target), any())).thenAnswer(inv -> {
            Money money = inv.getArgument(0);
            LocalDate date = inv.getArgument(2);
            return Optional.of(new ConvertedMoney(money.amount(), money.currency(), date));
        });
    }

    /** A rate table keyed by {@code "CURRENCY|DATE"} converting to {@code target}, backing both
     * {@code rate} (used for cost basis, trade-date FX) and {@code convert} (used for market
     * value, {@code asOf} FX). */
    private void stubFxTable(CurrencyCode target, Map<String, BigDecimal> rates) {
        lenient().when(fxConversion.rate(any(), eq(target), any())).thenAnswer(inv -> {
            CurrencyCode from = inv.getArgument(0);
            LocalDate date = inv.getArgument(2);
            if (from == target) {
                return Optional.of(BigDecimal.ONE);
            }
            return Optional.ofNullable(rates.get(from.name() + "|" + date));
        });
        lenient().when(fxConversion.convert(any(), eq(target), any())).thenAnswer(inv -> {
            Money money = inv.getArgument(0);
            LocalDate date = inv.getArgument(2);
            if (money.currency() == target) {
                return Optional.of(new ConvertedMoney(money.amount(), money.currency(), date));
            }
            BigDecimal rate = rates.get(money.currency().name() + "|" + date);
            if (rate == null) {
                return Optional.empty();
            }
            return Optional.of(new ConvertedMoney(money.amount().multiply(rate), target, date));
        });
    }

    @Test
    void singleCurrencyPortfolioValuesCorrectly() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                deposit("2000.00", "2025-12-31T00:00:00Z"),
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("150.0000"), CurrencyCode.USD, AS_OF)));
        stubIdentityFx(CurrencyCode.USD);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.marketValue()).isEqualByComparingTo("1500.0000");
        assertThat(result.costBasis()).isEqualByComparingTo("1000.0000");
        assertThat(result.cashBalance()).isEqualByComparingTo("1000.0000");
        assertThat(result.totalValue()).isEqualByComparingTo("2500.0000");
        assertThat(result.unrealisedPnl()).isEqualByComparingTo("500.0000");
        assertThat(result.unrealisedPnlPct()).isEqualByComparingTo("50.0000");
        assertThat(result.holdingCount()).isEqualTo(1);
        assertThat(result.stale()).isFalse();
    }

    @Test
    void threeCurrencyPortfolioValuesCorrectlyInBase() {
        Portfolio inrPortfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.INR, Instant.now(), Instant.now());
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(inrPortfolio);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                buy(RELIANCE_ID, "10", "1000.00", CurrencyCode.INR, "2026-01-01T00:00:00Z"),
                buy(SHEL_ID, "40", "20.00", CurrencyCode.GBP, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(instrumentRepository.findById(RELIANCE_ID)).thenReturn(Optional.of(instrument(RELIANCE_ID, "RELIANCE", CurrencyCode.INR)));
        when(instrumentRepository.findById(SHEL_ID)).thenReturn(Optional.of(instrument(SHEL_ID, "SHEL", CurrencyCode.GBP)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("150.0000"), CurrencyCode.USD, AS_OF)));
        when(priceLookup.priceOn(RELIANCE_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("1200.0000"), CurrencyCode.INR, AS_OF)));
        when(priceLookup.priceOn(SHEL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("25.0000"), CurrencyCode.GBP, AS_OF)));

        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("USD|" + TRADE_DATE, new BigDecimal("80"));
        rates.put("USD|" + AS_OF, new BigDecimal("85"));
        rates.put("GBP|" + TRADE_DATE, new BigDecimal("100"));
        rates.put("GBP|" + AS_OF, new BigDecimal("110"));
        stubFxTable(CurrencyCode.INR, rates);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        // AAPL: cost 10*100*80=80000, mv 10*150*85=127500
        // RELIANCE: cost 10*1000*1=10000, mv 10*1200*1=12000
        // SHEL: cost 40*20*100=80000, mv 40*25*110=110000
        assertThat(result.costBasis()).isEqualByComparingTo("170000.0000");
        assertThat(result.marketValue()).isEqualByComparingTo("249500.0000");
        assertThat(result.unrealisedPnl()).isEqualByComparingTo("79500.0000");
        assertThat(result.holdingCount()).isEqualTo(3);
        assertThat(result.currency()).isEqualTo(CurrencyCode.INR);
    }

    @Test
    void shouldReturnZeroValuationForEmptyPortfolio() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.marketValue()).isEqualByComparingTo("0.0000");
        assertThat(result.costBasis()).isEqualByComparingTo("0.0000");
        assertThat(result.cashBalance()).isEqualByComparingTo("0.0000");
        assertThat(result.totalValue()).isEqualByComparingTo("0.0000");
        assertThat(result.unrealisedPnlPct()).isNull();
        assertThat(result.holdingCount()).isZero();
        assertThat(result.stale()).isFalse();
    }

    @Test
    void shouldReturnNullPnlPercentWhenCostBasisIsZero() {
        // A gifted position: BUY at price 0, so cost basis is zero but it still carries value.
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "0.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("50.0000"), CurrencyCode.USD, AS_OF)));
        stubIdentityFx(CurrencyCode.USD);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.costBasis()).isEqualByComparingTo("0.0000");
        assertThat(result.marketValue()).isEqualByComparingTo("500.0000");
        assertThat(result.unrealisedPnlPct()).isNull();
    }

    @Test
    void shouldFallBackToLatestStoredPriceWhenNoPriceForDate() {
        LocalDate olderDate = AS_OF.minusDays(3);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("140.0000"), CurrencyCode.USD, olderDate)));
        stubIdentityFx(CurrencyCode.USD);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.marketValue()).isEqualByComparingTo("1400.0000");
        assertThat(result.priceAsOf()).isEqualTo(olderDate);
        assertThat(result.stale()).isTrue();
    }

    @Test
    void noPriceAnywhereReturnsNullMarketValueButStillReports() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(Optional.empty());
        stubIdentityFx(CurrencyCode.USD);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.marketValue()).isNull();
        assertThat(result.totalValue()).isNull();
        assertThat(result.unrealisedPnl()).isNull();
        assertThat(result.stale()).isTrue();
        assertThat(result.costBasis()).isEqualByComparingTo("1000.0000");
    }

    /**
     * TEST_PLAN.md §4.3's named negative, at this layer. Captures <b>every</b> date this service
     * asks for rather than spot-checking {@code asOf + 1}: a valuation that reached forward for
     * any later date would be inventing history. The other half of the rule — that the lookup
     * itself never <i>returns</i> a future row — is asserted against the day-by-day walk in
     * {@code PerformanceServiceTest#shouldNeverForwardFillFromAFuturePrice} and enforced in SQL
     * by {@code price_date <= :onOrBefore}.
     */
    @Test
    void shouldNeverRequestAPriceDatedAfterAsOf() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                buy(RELIANCE_ID, "5", "900.00", CurrencyCode.USD, "2026-02-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(instrumentRepository.findById(RELIANCE_ID)).thenReturn(Optional.of(instrument(RELIANCE_ID, "RELIANCE", CurrencyCode.USD)));
        when(priceLookup.priceOn(anyLong(), any())).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("150.0000"), CurrencyCode.USD, AS_OF)));
        stubIdentityFx(CurrencyCode.USD);

        service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        ArgumentCaptor<LocalDate> requestedDates = ArgumentCaptor.forClass(LocalDate.class);
        verify(priceLookup, atLeastOnce()).priceOn(anyLong(), requestedDates.capture());
        assertThat(requestedDates.getAllValues()).isNotEmpty().allSatisfy(date ->
                assertThat(date).isBeforeOrEqualTo(AS_OF));
    }

    @Test
    void asOfBeforeFirstTransactionThrowsBadRequest() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-06-15T00:00:00Z")));

        assertThatThrownBy(() -> service.valuate(USER_ID, PORTFOLIO_ID, LocalDate.parse("2026-01-01"), null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void cashOnlyPortfolioValuesCorrectly() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                deposit("500.00", "2026-01-01T00:00:00Z")));

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.holdingCount()).isZero();
        assertThat(result.marketValue()).isEqualByComparingTo("0.0000");
        assertThat(result.cashBalance()).isEqualByComparingTo("500.0000");
        assertThat(result.totalValue()).isEqualByComparingTo("500.0000");
        assertThat(result.unrealisedPnlPct()).isNull();
    }

    @Test
    void changingBaseCurrencyChangesValuesButWritesNothing() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z")));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(instrument(AAPL_ID, "AAPL", CurrencyCode.USD)));
        when(priceLookup.priceOn(AAPL_ID, AS_OF)).thenReturn(
                Optional.of(new PricePoint(new BigDecimal("150.0000"), CurrencyCode.USD, AS_OF)));
        stubIdentityFx(CurrencyCode.USD);
        Map<String, BigDecimal> rates = new HashMap<>();
        rates.put("USD|" + TRADE_DATE, new BigDecimal("80"));
        rates.put("USD|" + AS_OF, new BigDecimal("85"));
        stubFxTable(CurrencyCode.INR, rates);

        ValuationResult inUsd = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, CurrencyCode.USD);
        ValuationResult inInr = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, CurrencyCode.INR);

        assertThat(inUsd.currency()).isEqualTo(CurrencyCode.USD);
        assertThat(inInr.currency()).isEqualTo(CurrencyCode.INR);
        assertThat(inUsd.marketValue()).isNotEqualByComparingTo(inInr.marketValue());
        verify(transactionRepository, never()).insert(any());
        verify(transactionRepository, never()).deleteByIdAndPortfolio(anyLong(), anyLong());
    }

    @Test
    void sellToZeroExcludesInstrumentFromMarketValueButKeepsRealisedPnl() {
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(
                buy(AAPL_ID, "10", "100.00", CurrencyCode.USD, "2026-01-01T00:00:00Z"),
                sell(AAPL_ID, "10", "120.00", CurrencyCode.USD, "2026-01-02T00:00:00Z")));
        stubIdentityFx(CurrencyCode.USD);

        ValuationResult result = service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null);

        assertThat(result.holdingCount()).isZero();
        assertThat(result.marketValue()).isEqualByComparingTo("0.0000");
        assertThat(result.realisedPnl()).isEqualByComparingTo("200.0000");
        verify(priceLookup, never()).priceOn(anyLong(), any());
    }

    @Test
    void portfolioNotFoundPropagatesNotFound() {
        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenThrow(new NotFoundException("portfolio", PORTFOLIO_ID));

        assertThatThrownBy(() -> service.valuate(USER_ID, PORTFOLIO_ID, AS_OF, null))
                .isInstanceOf(NotFoundException.class);
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
