package com.protify.portfolio.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.holding.Holding;
import com.protify.portfolio.holding.HoldingRepository;
import com.protify.portfolio.holding.HoldingState;
import com.protify.portfolio.holding.ProjectionContext;
import com.protify.portfolio.holding.ProjectionEngine;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.portfolio.PortfolioRepository;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationSnapshotRepository;
import com.protify.portfolio.valuation.ValuationSnapshotService;
import java.time.Clock;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * "The assessor's favourite" (TEST_PLAN.md §4.5): deleting a mid-history transaction must
 * rebuild the holding to look exactly like the remaining transactions had been replayed into a
 * fresh, empty portfolio — proving the projection is genuinely derived from {@code txn}, not a
 * mutable ledger that drifts.
 */
class MidHistoryDeleteIT extends AbstractIntegrationTest {

    private NamedParameterJdbcTemplate jdbc;
    private TransactionRepository transactionRepository;
    private HoldingRepository holdingRepository;
    private TransactionService service;
    private long userId;
    private long portfolioId;
    private long aaplId;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES ('sub-1', 'u1@example.com')", Map.of());
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = 'sub-1'", Map.of(), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Main', 'USD')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId",
                Map.of("userId", userId), Long.class);
        aaplId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'AAPL'", Map.of(), Long.class);

        transactionRepository = new TransactionRepository(jdbc);
        holdingRepository = new HoldingRepository(jdbc);
        PortfolioService portfolioService = new PortfolioService(new PortfolioRepository(jdbc));
        InstrumentRepository instrumentRepository = new InstrumentRepository(jdbc);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        service = new TransactionService(portfolioService, instrumentRepository, transactionRepository,
                holdingRepository, new ValuationSnapshotService(new ValuationSnapshotRepository(jdbc), Clock.systemUTC()),
                transactionManager);
    }

    private Txn txn(TransactionType type, String qty, String price, String executedAt) {
        return new Txn(null, portfolioId, aaplId, type, new BigDecimal(qty), new BigDecimal(price),
                BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(executedAt), null, null);
    }

    @Test
    void shouldRebuildProjectionIdenticallyWhenOldestTransactionDeleted() {
        long firstId = transactionRepository.insert(txn(TransactionType.BUY, "10", "100.00", "2026-01-01T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.BUY, "5", "110.00", "2026-01-02T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.SELL, "3", "120.00", "2026-01-03T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.BUY, "2", "130.00", "2026-01-04T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.SELL, "4", "140.00", "2026-01-05T00:00:00Z"));
        seedProjectionFromCurrentHistory();

        service.delete(userId, portfolioId, firstId);

        List<Txn> remaining = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        assertThat(remaining).hasSize(4);
        HoldingState expected = ProjectionEngine.project(remaining, ProjectionContext.identity(CurrencyCode.USD))
                .holdingsByInstrument().get(aaplId);

        List<Holding> actual = holdingRepository.findByPortfolio(portfolioId);
        assertThat(actual).hasSize(1);
        Holding holding = actual.get(0);
        assertThat(holding.quantity()).isEqualByComparingTo(expected.quantity());
        assertThat(holding.avgCost()).isEqualByComparingTo(expected.avgCost());
        assertThat(holding.realisedPnl()).isEqualByComparingTo(expected.realisedPnl());
    }

    @Test
    void deletingATransactionThatLeavesALaterSellUncoveredRollsBackEverything() {
        long firstId = transactionRepository.insert(txn(TransactionType.BUY, "10", "100.00", "2026-01-01T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.SELL, "10", "120.00", "2026-01-02T00:00:00Z"));
        seedProjectionFromCurrentHistory();

        long txnCountBefore = countTxnRows();
        long holdingCountBefore = countHoldingRows();

        assertThatThrownBy(() -> service.delete(userId, portfolioId, firstId))
                .isInstanceOf(InsufficientQuantityException.class);

        assertThat(countTxnRows()).isEqualTo(txnCountBefore);
        assertThat(countHoldingRows()).isEqualTo(holdingCountBefore);
        List<Txn> stillThere = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        assertThat(stillThere).extracting(Txn::id).contains(firstId);
    }

    /**
     * TEST_PLAN.md §4.5's third bullet: "delete inside a transaction that then throws → assert
     * neither the delete nor the rebuild persisted."
     *
     * <p>The delete succeeds on its own terms here — no uncovered SELL, unlike the test above —
     * and it is the <b>caller's</b> transaction that fails afterwards.
     * {@link TransactionService}'s {@code TransactionTemplate} is {@code PROPAGATION_REQUIRED},
     * so it joins the outer transaction rather than committing independently, and the outer
     * rollback must take the {@code txn} delete and the whole {@code holding} rebuild with it.
     * If that propagation were ever changed to {@code REQUIRES_NEW}, this is the test that
     * notices (CLAUDE.md non-negotiable #12).
     */
    @Test
    void shouldPersistNeitherTheDeleteNorTheRebuildWhenTheSurroundingTransactionThrows() {
        long firstId = transactionRepository.insert(txn(TransactionType.BUY, "10", "100.00", "2026-01-01T00:00:00Z"));
        transactionRepository.insert(txn(TransactionType.BUY, "5", "110.00", "2026-01-02T00:00:00Z"));
        seedProjectionFromCurrentHistory();

        List<Holding> holdingsBefore = holdingRepository.findByPortfolio(portfolioId);
        long txnCountBefore = countTxnRows();

        TransactionTemplate outer = new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));

        assertThatThrownBy(() -> outer.executeWithoutResult(status -> {
            service.delete(userId, portfolioId, firstId);
            throw new IllegalStateException("caller failed after the delete");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(countTxnRows()).isEqualTo(txnCountBefore);
        assertThat(transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId))
                .extracting(Txn::id).contains(firstId);

        List<Holding> holdingsAfter = holdingRepository.findByPortfolio(portfolioId);
        assertThat(holdingsAfter).hasSameSizeAs(holdingsBefore);
        for (int i = 0; i < holdingsBefore.size(); i++) {
            assertThat(holdingsAfter.get(i).instrumentId()).isEqualTo(holdingsBefore.get(i).instrumentId());
            assertThat(holdingsAfter.get(i).quantity()).isEqualByComparingTo(holdingsBefore.get(i).quantity());
            assertThat(holdingsAfter.get(i).avgCost()).isEqualByComparingTo(holdingsBefore.get(i).avgCost());
            assertThat(holdingsAfter.get(i).realisedPnl()).isEqualByComparingTo(holdingsBefore.get(i).realisedPnl());
        }
    }

    /** Populates {@code holding} the same way a real write path would, so the "rolled back"
     * assertions above have a real baseline row to prove is untouched. */
    private void seedProjectionFromCurrentHistory() {
        List<Txn> history = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        var projected = ProjectionEngine.project(history, ProjectionContext.identity(CurrencyCode.USD));
        projected.holdingsByInstrument().values().forEach(state -> holdingRepository.upsert(portfolioId, state));
    }

    private long countTxnRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM txn WHERE portfolio_id = :portfolioId",
                Map.of("portfolioId", portfolioId), Long.class);
        return count == null ? 0 : count;
    }

    private long countHoldingRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM holding WHERE portfolio_id = :portfolioId",
                Map.of("portfolioId", portfolioId), Long.class);
        return count == null ? 0 : count;
    }
}
