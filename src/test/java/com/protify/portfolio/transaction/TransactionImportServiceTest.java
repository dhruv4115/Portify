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
import com.protify.portfolio.common.error.NotFoundException;
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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

/**
 * Unit tests, every collaborator mocked — same setup as {@link TransactionServiceTest}: a mocked
 * {@link PlatformTransactionManager} is enough, because {@code TransactionTemplate} still runs the
 * callback synchronously against it.
 *
 * <p>What is worth testing here is what makes bulk import different from n single writes: the
 * batch is folded as one history, so it can be internally consistent; it is all-or-nothing; and a
 * rejection has to name the row that caused it, not merely fail.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TransactionImportServiceTest {

    private static final long USER_ID = 1L;
    private static final long PORTFOLIO_ID = 7L;
    /**
     * Deliberately a single digit that also appears inside the quantities the over-sell test uses.
     * {@link com.protify.portfolio.common.error.InsufficientQuantityException} carries the
     * instrument id where a symbol belongs, and swapping the symbol back in by string replacement
     * quietly rewrites the digits of the numbers around it — "Cannot sell 500 of 5; holding is 15."
     * became "Cannot sell JPM00 of JPM; holding is 1JPM." An id of 100 hid that for two rounds.
     */
    private static final long AAPL_ID = 5L;

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

    private TransactionImportService service;
    private Instrument aapl;

    @BeforeEach
    void setUp() {
        service = new TransactionImportService(portfolioService, instrumentRepository,
                transactionRepository, holdingRepository, snapshotService, transactionManager);
        aapl = new Instrument(AAPL_ID, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD,
                "NASDAQ", "Technology", Instant.now());

        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID)).thenReturn(new Portfolio(
                PORTFOLIO_ID, USER_ID, "Main", CurrencyCode.USD, Instant.now(), Instant.now()));
        when(instrumentRepository.findBySymbol("AAPL")).thenReturn(Optional.of(aapl));
        when(instrumentRepository.findById(AAPL_ID)).thenReturn(Optional.of(aapl));
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of());
    }

    private static RecordTransactionCommand buy(String qty, String price, String on) {
        return new RecordTransactionCommand("AAPL", TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(on), null);
    }

    private static RecordTransactionCommand sell(String qty, String price, String on) {
        return new RecordTransactionCommand("AAPL", TransactionType.SELL, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(on), null);
    }

    private static RecordTransactionCommand deposit(String amount, String on) {
        return new RecordTransactionCommand(null, TransactionType.DEPOSIT, BigDecimal.ZERO,
                new BigDecimal(amount), BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(on), null);
    }

    @Test
    void writesEveryRowAndRebuildsTheWholeProjection() {
        var result = service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                deposit("10000", "2026-01-01T00:00:00Z"),
                buy("10", "150", "2026-01-02T00:00:00Z")), false);

        assertThat(result.imported()).isEqualTo(2);
        assertThat(result.warnings()).isEmpty();
        verify(transactionRepository, times(2)).insert(any(Txn.class));

        // Holdings are cleared and rewritten, not upserted one instrument at a time: an import
        // lands in the middle of history, and a back-dated BUY moves every later average cost.
        verify(holdingRepository).deleteAllForPortfolio(PORTFOLIO_ID);
        verify(holdingRepository).upsert(eq(PORTFOLIO_ID), any(HoldingState.class));
    }

    @Test
    void acceptsASellCoveredByABuyEarlierInTheSameFile() {
        // The portfolio holds nothing. Applied one at a time this file would be rejected on its
        // second row; folded as one history — which is what an import is — it is perfectly valid.
        var result = service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                buy("10", "150", "2026-01-02T00:00:00Z"),
                sell("4", "160", "2026-01-03T00:00:00Z")), false);

        assertThat(result.imported()).isEqualTo(2);
    }

    @Test
    void invalidatesCachedValuationsFromTheEarliestImportedDate() {
        service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                buy("1", "150", "2026-05-20T00:00:00Z"),
                buy("1", "150", "2026-01-09T00:00:00Z"),
                buy("1", "150", "2026-03-01T00:00:00Z")), false);

        // Not the first row's date and not the last row's — the earliest, because everything from
        // there onward is what the new rows can have changed.
        verify(snapshotService).invalidateFrom(PORTFOLIO_ID, LocalDate.of(2026, 1, 9));
    }

    @Test
    void namesTheRowWhoseSellIsNotCoveredAndWritesNothing() {
        assertThatThrownBy(() -> service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                buy("10", "150", "2026-01-02T00:00:00Z"),
                sell("4", "160", "2026-01-03T00:00:00Z"),
                sell("500", "160", "2026-01-04T00:00:00Z")), false))
                .isInstanceOf(RowRejectedException.class)
                .satisfies(thrown -> assertThat(((RowRejectedException) thrown).index()).isEqualTo(2))
                // The engine identifies the instrument by id; the symbol the user wrote is put
                // back — without disturbing a single digit of the quantities around it.
                .hasMessage("Cannot sell 500 of AAPL; holding is 6.");

        verify(transactionRepository, never()).insert(any());
        verify(holdingRepository, never()).deleteAllForPortfolio(anyLong());
    }

    @Test
    void namesTheRowWithAnUnknownSymbol() {
        when(instrumentRepository.findBySymbol("NOPE")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                buy("10", "150", "2026-01-02T00:00:00Z"),
                new RecordTransactionCommand("NOPE", TransactionType.BUY, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-03T00:00:00Z"), null)), false))
                .isInstanceOf(RowRejectedException.class)
                .satisfies(thrown -> assertThat(((RowRejectedException) thrown).index()).isEqualTo(1))
                .hasMessageContaining("NOPE");

        verify(transactionRepository, never()).insert(any());
    }

    @Test
    void namesTheRowWhoseCurrencyIsNotTheInstrumentsOwn() {
        assertThatThrownBy(() -> service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                new RecordTransactionCommand("AAPL", TransactionType.BUY, BigDecimal.ONE,
                        BigDecimal.TEN, BigDecimal.ZERO, CurrencyCode.INR,
                        Instant.parse("2026-01-03T00:00:00Z"), null)), false))
                .isInstanceOf(RowRejectedException.class)
                .hasMessageContaining("AAPL trades in USD");

        verify(transactionRepository, never()).insert(any());
    }

    @Test
    void namesTheRowThatBreaksAShapeRuleWithoutTouchingTheDatabase() {
        assertThatThrownBy(() -> service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                buy("10", "150", "2026-01-02T00:00:00Z"),
                new RecordTransactionCommand("AAPL", TransactionType.DEPOSIT, BigDecimal.ZERO,
                        BigDecimal.TEN, BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-01-03T00:00:00Z"), null)), false))
                .isInstanceOf(RowRejectedException.class)
                .satisfies(thrown -> assertThat(((RowRejectedException) thrown).index()).isEqualTo(1))
                .hasMessageContaining("must not reference an instrument");

        // Shape rules need no ledger, so they are checked before the portfolio is even locked.
        verify(portfolioService, never()).lockForUpdate(anyLong(), anyLong());
    }

    @Test
    void warnsWhenTheBatchLeavesCashNegativeWithoutRefusingIt() {
        // A portfolio whose deposits were never recorded is a normal way to start, so this is a
        // warning on a successful import, exactly as it is for a single BUY.
        var result = service.importAll(USER_ID, PORTFOLIO_ID,
                List.of(buy("10", "150", "2026-01-02T00:00:00Z")), false);

        assertThat(result.imported()).isEqualTo(1);
        assertThat(result.warnings()).singleElement().asString().contains("negative");
    }

    @Test
    void dryRunReportsWhatWouldHappenAndRollsBack() {
        TransactionStatus status = org.mockito.Mockito.mock(TransactionStatus.class);
        when(transactionManager.getTransaction(any())).thenReturn(status);

        var result = service.importAll(USER_ID, PORTFOLIO_ID, List.of(
                deposit("10000", "2026-01-01T00:00:00Z"),
                buy("10", "150", "2026-01-02T00:00:00Z")), true);

        assertThat(result.imported()).isEqualTo(2);
        // The writes are issued and then thrown away by the rollback. That is the point: a preview
        // that skipped them would be a different code path, and could pass where the real one fails.
        verify(transactionRepository, times(2)).insert(any(Txn.class));
        verify(status).setRollbackOnly();
    }

    @Test
    void anEmptyBatchIsANoOpRatherThanALock() {
        var result = service.importAll(USER_ID, PORTFOLIO_ID, List.of(), false);

        assertThat(result.imported()).isZero();
        verify(portfolioService, never()).lockForUpdate(anyLong(), anyLong());
    }

    @Test
    void aPortfolioThatIsNotYoursIsStillANotFound() {
        when(portfolioService.lockForUpdate(USER_ID, PORTFOLIO_ID))
                .thenThrow(new NotFoundException("portfolio", PORTFOLIO_ID));

        assertThatThrownBy(() -> service.importAll(USER_ID, PORTFOLIO_ID,
                List.of(deposit("100", "2026-01-01T00:00:00Z")), false))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void foldsTheImportOnTopOfTheHistoryAlreadyStored() {
        Txn priorBuy = new Txn(1L, PORTFOLIO_ID, AAPL_ID, TransactionType.BUY, new BigDecimal("50"),
                new BigDecimal("100"), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2025-12-01T00:00:00Z"), null, null);
        when(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).thenReturn(List.of(priorBuy));

        // Sells more than the file itself buys — only the stored history makes it legal.
        var result = service.importAll(USER_ID, PORTFOLIO_ID,
                List.of(sell("30", "160", "2026-01-03T00:00:00Z")), false);

        assertThat(result.imported()).isEqualTo(1);
        ArgumentCaptor<HoldingState> state = ArgumentCaptor.forClass(HoldingState.class);
        verify(holdingRepository).upsert(eq(PORTFOLIO_ID), state.capture());
        assertThat(state.getValue().quantity()).isEqualByComparingTo("20");
    }
}
