package com.protify.portfolio.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.CurrencyMismatchException;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.holding.HoldingRepository;
import com.protify.portfolio.holding.HoldingState;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationSnapshotService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests, every collaborator mocked (day-3-dev-A.md D3-A1) — no database, no Spring
 * context. A {@link PlatformTransactionManager} mock is enough: {@link
 * org.springframework.transaction.support.TransactionTemplate} still invokes the callback
 * synchronously against it, so business logic runs exactly as it would in production.
 */
@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;
    private static final long AAPL_ID = 100L;

    @Mock
    private PortfolioService portfolioService;
    @Mock
    private InstrumentRepository instrumentRepository;
    @Mock
    private TransactionRepository transactionRepository;
    @Mock
    private HoldingRepository holdingRepository;
    @Mock
    private ValuationSnapshotService snapshotService;
    @Mock
    private PlatformTransactionManager transactionManager;

    private TransactionService service;
    private Portfolio portfolio;
    private Instrument aapl;

    @BeforeEach
    void setUp() {
        service = new TransactionService(portfolioService, instrumentRepository, transactionRepository,
                holdingRepository, snapshotService, transactionManager);
        portfolio = new Portfolio(PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.USD, Instant.now(), Instant.now());
        aapl = new Instrument(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD, "NASDAQ", "Technology", Instant.now());
    }

    private RecordTransactionCommand buy(String qty, String price, String fees) {
        return new RecordTransactionCommand("AAPL", TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), new BigDecimal(fees), CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    private RecordTransactionCommand sell(String qty, String price, String fees) {
        return new RecordTransactionCommand("AAPL", TransactionType.SELL, new BigDecimal(qty),
                new BigDecimal(price), new BigDecimal(fees), CurrencyCode.USD,
                Instant.parse("2026-01-03T00:00:00Z"), null);
    }

    private RecordTransactionCommand buyOf(String symbol, String qty, String price, CurrencyCode currency) {
        return new RecordTransactionCommand(symbol, TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, currency,
                Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    private void givenExistingHolding(BigDecimal quantity, BigDecimal avgCost) {
        Txn priorBuy = new Txn(1L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, quantity, avgCost,
                BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2026-01-01T00:00:00Z"), null, null);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(priorBuy));
    }

    private Txn deposit(String amount) {
        return new Txn(1L, PORTFOLIO_ID, null, TransactionType.DEPOSIT, BigDecimal.ZERO,
                new BigDecimal(amount), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2025-12-31T00:00:00Z"), null, null);
    }

    @Test
    void happyBuyInsertsTxnAndUpsertsHolding() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(deposit("100000.00")));
        when(transactionRepository.insert(any())).thenReturn(91L);

        RecordTransactionResult result = service.record(USER_ID, PORTFOLIO_ID, buy("10", "100.00", "5.00"));

        assertThat(result.txnId()).isEqualTo(91L);
        assertThat(result.warnings()).isEmpty();
        verify(holdingRepository).upsert(eq(PORTFOLIO_ID), any(HoldingState.class));
    }

    @Test
    void happySellReducesHoldingAndRecordsRealisedPnl() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        givenExistingHolding(new BigDecimal("10.000000"), new BigDecimal("100.0000"));
        when(transactionRepository.insert(any())).thenReturn(92L);

        RecordTransactionResult result = service.record(USER_ID, PORTFOLIO_ID, sell("4", "150.00", "0"));

        assertThat(result.txnId()).isEqualTo(92L);
        verify(holdingRepository).upsert(anyLong(), any(HoldingState.class));
    }

    @Test
    void sellExceedingHoldingRejectsWithNothingWritten() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        givenExistingHolding(new BigDecimal("10.000000"), new BigDecimal("100.0000"));

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, sell("50", "150.00", "0")))
                .isInstanceOf(InsufficientQuantityException.class);

        verify(transactionRepository, never()).insert(any());
        verify(holdingRepository, never()).upsert(anyLong(), any());
    }

    @Test
    void shouldRejectUnknownSymbolBeforeAnyWrite() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("TSLAA")).thenReturn(Optional.empty());
        RecordTransactionCommand command = new RecordTransactionCommand("TSLAA", TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("10"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null);

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, command))
                .isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).insert(any());
        verify(transactionRepository, never()).findByPortfolioOrderByExecutedAt(anyLong());
    }

    @Test
    void shouldRejectTransactionWhenCurrencyDoesNotMatchInstrument() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        RecordTransactionCommand command = new RecordTransactionCommand("AAPL", TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("10"), BigDecimal.ZERO, CurrencyCode.INR,
                Instant.parse("2026-01-02T00:00:00Z"), null);

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, command))
                .isInstanceOf(CurrencyMismatchException.class);

        verify(transactionRepository, never()).insert(any());
    }

    /**
     * TEST_PLAN.md §4.6's <b>positive</b> case, and PLAN.md §2.1's headline feature. The
     * currency constraint is per <i>instrument</i>, not per portfolio: one USD portfolio holding
     * AAPL (USD), RELIANCE (INR) and SHEL (GBP) is exactly what the customer asked for. Without
     * this test an over-eager "reject a currency that differs from the portfolio's base" rule
     * would pass {@link #shouldRejectTransactionWhenCurrencyDoesNotMatchInstrument} and every
     * other negative here, while breaking the product.
     */
    @Test
    void shouldAcceptMixedCurrencyPortfolio() {
        Instrument reliance = new Instrument(200L, "RELIANCE", "Reliance Industries Limited",
                AssetType.STOCK, CurrencyCode.INR, "NSE", "Energy", Instant.now());
        Instrument shel = new Instrument(300L, "SHEL", "Shell plc",
                AssetType.STOCK, CurrencyCode.GBP, "LSE", "Energy", Instant.now());

        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        when(instrumentRepository.findBySymbol("RELIANCE")).thenReturn(Optional.of(reliance));
        when(instrumentRepository.findBySymbol("SHEL")).thenReturn(Optional.of(shel));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
        when(transactionRepository.insert(any())).thenReturn(101L, 102L, 103L);

        // The portfolio's base currency is USD throughout — see setUp().
        service.record(USER_ID, PORTFOLIO_ID, buyOf("AAPL", "10", "100.00", CurrencyCode.USD));
        service.record(USER_ID, PORTFOLIO_ID, buyOf("RELIANCE", "10", "1000.00", CurrencyCode.INR));
        service.record(USER_ID, PORTFOLIO_ID, buyOf("SHEL", "40", "20.00", CurrencyCode.GBP));

        verify(transactionRepository, times(3)).insert(any());
        verify(holdingRepository, times(3)).upsert(eq(PORTFOLIO_ID), any(HoldingState.class));
    }

    /**
     * TEST_PLAN.md §4.10's case-insensitivity and trimming, at the layer that decides whether a
     * transaction is written. {@code InstrumentRepositoryIT} proves the SQL does the {@code
     * TRIM}/{@code UPPER}; this proves nothing upstream defeats it before the lookup happens.
     */
    @Test
    void shouldResolveASymbolWithSurroundingWhitespaceAndMixedCase() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol(" aapl ")).thenReturn(Optional.of(aapl));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
        when(transactionRepository.insert(any())).thenReturn(95L);

        RecordTransactionResult result =
                service.record(USER_ID, PORTFOLIO_ID, buyOf(" aapl ", "1", "100.00", CurrencyCode.USD));

        assertThat(result.txnId()).isEqualTo(95L);
        // Resolved to the real AAPL row, so the txn carries its id and not the raw string.
        ArgumentCaptor<Txn> inserted = ArgumentCaptor.forClass(Txn.class);
        verify(transactionRepository).insert(inserted.capture());
        assertThat(inserted.getValue().instrumentId()).isEqualTo(AAPL_ID);
    }

    @Test
    void buyOverCashSucceedsWithWarning() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
        when(transactionRepository.insert(any())).thenReturn(93L);

        RecordTransactionResult result = service.record(USER_ID, PORTFOLIO_ID, buy("100", "1000.00", "0"));

        assertThat(result.warnings()).isNotEmpty();
    }

    @Test
    void zeroQuantityIsRejected() {
        RecordTransactionCommand command = new RecordTransactionCommand("AAPL", TransactionType.BUY,
                BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null);

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, command))
                .isInstanceOf(ValidationException.class);

        verify(portfolioService, never()).lockForUpdate(anyLong(), anyLong());
    }

    @Test
    void futureExecutedAtIsRejected() {
        RecordTransactionCommand command = new RecordTransactionCommand("AAPL", TransactionType.BUY,
                new BigDecimal("1"), new BigDecimal("10"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.now().plus(1, ChronoUnit.DAYS), null);

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, command))
                .isInstanceOf(ValidationException.class);

        verify(portfolioService, never()).lockForUpdate(anyLong(), anyLong());
    }

    @Test
    void fractionalQuantityAtSixDecimalPlacesIsAccepted() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
        when(transactionRepository.insert(any())).thenReturn(94L);

        RecordTransactionCommand command = new RecordTransactionCommand("AAPL", TransactionType.BUY,
                new BigDecimal("0.123456"), new BigDecimal("10.0000"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null);

        RecordTransactionResult result = service.record(USER_ID, PORTFOLIO_ID, command);

        assertThat(result.txnId()).isEqualTo(94L);
    }

    @Test
    void portfolioNotFoundRejectsWithNothingWritten() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenThrow(new NotFoundException("portfolio", PORTFOLIO_ID));

        assertThatThrownBy(() -> service.record(USER_ID, PORTFOLIO_ID, buy("1", "10", "0")))
                .isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).insert(any());
    }

    @Test
    void deleteRebuildsHoldingsFromRemainingTransactions() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        Txn doomed = new Txn(1L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("3.000000"),
                new BigDecimal("40.0000"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null, null);
        when(transactionRepository.findByIdAndPortfolio(1L, PORTFOLIO_ID)).thenReturn(Optional.of(doomed));
        Txn remaining = new Txn(2L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("5.000000"),
                new BigDecimal("50.0000"), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse("2026-01-01T00:00:00Z"), null, null);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(remaining));

        service.delete(USER_ID, PORTFOLIO_ID, 1L);

        verify(transactionRepository).deleteByIdAndPortfolio(1L, PORTFOLIO_ID);
        verify(holdingRepository).deleteAllForPortfolio(PORTFOLIO_ID);
        verify(holdingRepository).upsert(anyLong(), any(HoldingState.class));
        // Every valuation from the deleted transaction's own date onward is now wrong (D5-A2).
        verify(snapshotService).invalidateFrom(PORTFOLIO_ID, LocalDate.parse("2026-01-02"));
    }

    @Test
    void deletingUnknownTransactionThrowsNotFound() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(portfolio);
        when(transactionRepository.findByIdAndPortfolio(999L, PORTFOLIO_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(USER_ID, PORTFOLIO_ID, 999L))
                .isInstanceOf(NotFoundException.class);

        verify(transactionRepository, never()).deleteByIdAndPortfolio(anyLong(), anyLong());
        verify(holdingRepository, never()).deleteAllForPortfolio(anyLong());
        // Nothing changed, so nothing is invalidated — a failed delete must not empty the cache.
        verify(snapshotService, never()).invalidateFrom(anyLong(), any());
    }
}
