package com.protify.portfolio.transaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.InsufficientQuantityException;
import com.protify.portfolio.holding.HoldingRepository;
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

/**
 * Real MySQL (Testcontainers), real {@link TransactionRepository}/{@link PortfolioRepository},
 * a mocked {@link HoldingRepository} standing in for "the projection upsert fails for whatever
 * reason" — day-3-dev-A.md D3-A1's last test case: a failure after the {@code txn} insert must
 * roll the insert back too, because both run inside {@link TransactionService}'s one DB
 * transaction (CLAUDE.md non-negotiable #12).
 */
class TransactionServiceIT extends AbstractIntegrationTest {

    private NamedParameterJdbcTemplate jdbc;
    private TransactionRepository transactionRepository;
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
    }

    private ValuationSnapshotService snapshotService() {
        return new ValuationSnapshotService(new ValuationSnapshotRepository(jdbc), Clock.systemUTC());
    }

    private TransactionService serviceWithHoldingRepository(HoldingRepository holdingRepository) {
        PortfolioRepository portfolioRepository = new PortfolioRepository(jdbc);
        PortfolioService portfolioService = new PortfolioService(portfolioRepository);
        InstrumentRepository instrumentRepository = new InstrumentRepository(jdbc);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        return new TransactionService(portfolioService, instrumentRepository, transactionRepository,
                holdingRepository, snapshotService(), transactionManager);
    }

    private RecordTransactionCommand buy(String qty, String price) {
        return new RecordTransactionCommand("AAPL", TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-02T00:00:00Z"), null);
    }

    private RecordTransactionCommand sell(String qty, String price) {
        return new RecordTransactionCommand("AAPL", TransactionType.SELL, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-03T00:00:00Z"), null);
    }

    @Test
    void projectionFailureRollsBackTheTxnInsert() {
        HoldingRepository failingHoldingRepository = mock(HoldingRepository.class);
        doThrow(new RuntimeException("simulated upsert failure"))
                .when(failingHoldingRepository).upsert(anyLong(), any());
        TransactionService service = serviceWithHoldingRepository(failingHoldingRepository);

        long countBefore = countTxnRows();

        assertThatThrownBy(() -> service.record(userId, portfolioId, buy("10", "100.00")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated upsert failure");

        assertThat(countTxnRows()).isEqualTo(countBefore);
    }

    @Test
    void successfulRecordCommitsBothTheTxnAndTheHolding() {
        HoldingRepository realHoldingRepository = new HoldingRepository(jdbc);
        TransactionService service = serviceWithHoldingRepository(realHoldingRepository);

        RecordTransactionResult result = service.record(userId, portfolioId, buy("10", "100.00"));

        assertThat(result.txnId()).isPositive();
        assertThat(countTxnRows()).isEqualTo(1);
        List<Long> holdingInstrumentIds = realHoldingRepository.findByPortfolio(portfolioId).stream()
                .map(com.protify.portfolio.holding.Holding::instrumentId).toList();
        assertThat(holdingInstrumentIds).containsExactly(aaplId);
    }

    /**
     * TEST_PLAN.md §4.1's "assert <b>nothing</b> was written", at the level it asks for: real
     * MySQL, real repositories, not a {@code verify(never())} on a mock. Hold 20, sell 50 — the
     * {@code txn} count is unchanged and {@code holding.quantity} is still exactly 20.
     */
    @Test
    void shouldWriteNothingWhenSellExceedsHolding() {
        TransactionService service = serviceWithHoldingRepository(new HoldingRepository(jdbc));
        service.record(userId, portfolioId, buy("20", "100.00"));

        long txnCountBefore = countTxnRows();

        assertThatThrownBy(() -> service.record(userId, portfolioId, sell("50", "150.00")))
                .isInstanceOf(InsufficientQuantityException.class);

        assertThat(countTxnRows()).isEqualTo(txnCountBefore);
        assertThat(heldQuantity()).isEqualByComparingTo("20.000000");
    }

    /**
     * TEST_PLAN.md §4.1's fractional boundary, end to end: {@code 0.523100} held, {@code
     * 0.523101} sold. A rounding slip anywhere between the DECIMAL(19,6) column and
     * {@code ProjectionEngine} shows up here and nowhere else.
     */
    @Test
    void shouldWriteNothingWhenSellExceedsAFractionalHoldingByOneMicroUnit() {
        TransactionService service = serviceWithHoldingRepository(new HoldingRepository(jdbc));
        service.record(userId, portfolioId, buy("0.523100", "10.00"));

        long txnCountBefore = countTxnRows();

        assertThatThrownBy(() -> service.record(userId, portfolioId, sell("0.523101", "12.00")))
                .isInstanceOf(InsufficientQuantityException.class);

        assertThat(countTxnRows()).isEqualTo(txnCountBefore);
        assertThat(heldQuantity()).isEqualByComparingTo("0.523100");

        // ...and the exact quantity still sells, leaving zero. The pair is the assertion.
        service.record(userId, portfolioId, sell("0.523100", "12.00"));
        assertThat(heldQuantity()).isEqualByComparingTo("0.000000");
    }

    private BigDecimal heldQuantity() {
        return jdbc.queryForObject("SELECT quantity FROM holding WHERE portfolio_id = :portfolioId",
                Map.of("portfolioId", portfolioId), BigDecimal.class);
    }

    private long countTxnRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM txn WHERE portfolio_id = :portfolioId",
                Map.of("portfolioId", portfolioId), Long.class);
        return count == null ? 0 : count;
    }
}
