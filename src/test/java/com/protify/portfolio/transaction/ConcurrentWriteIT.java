package com.protify.portfolio.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
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
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * TEST_PLAN.md §4.8 · day-4-dev-A.md D4-A2. Two writers, one portfolio, real commits.
 *
 * <p><b>Deliberately not {@code @Transactional}</b>, unlike every other {@code *IT}
 * (TEST_PLAN.md §6 names this class as the exception): a test that rolls back cannot observe a
 * lost update, because the whole point is what two <i>committed</i> transactions leave behind.
 * Cleanup is therefore explicit, before and after each test.
 *
 * <p><b>Verified to be a real test.</b> With the {@code FOR UPDATE} removed from
 * {@link PortfolioRepository#lockForUpdate}, {@link #shouldNotLoseUpdateWhenTwoBuysCommitSimultaneously}
 * fails with a final quantity of 1 instead of 2 — both threads read an empty history, both
 * compute "you now hold 1", and the second {@code ON DUPLICATE KEY UPDATE} overwrites the first
 * rather than adding to it. A concurrency test that passes without the lock proves nothing.
 *
 * <p>Lock ordering is always {@code portfolio} → {@code txn} → {@code holding}, in
 * {@code doRecord} and {@code doDelete} alike, which is why {@link
 * #shouldNotDeadlockWhenABuyAndADeleteContendOnTheSamePortfolio} can assert termination rather
 * than hope for it.
 */
class ConcurrentWriteIT extends AbstractIntegrationTest {

    private static final Duration NO_DEADLOCK_BUDGET = Duration.ofSeconds(5);

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
        deleteEverything();

        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES ('concurrent-sub', 'c@example.com')", Map.of());
        userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = 'concurrent-sub'", Map.of(), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Main', 'USD')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId",
                Map.of("userId", userId), Long.class);
        aaplId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'AAPL'", Map.of(), Long.class);

        transactionRepository = new TransactionRepository(jdbc);
        holdingRepository = new HoldingRepository(jdbc);
        service = new TransactionService(
                new PortfolioService(new PortfolioRepository(jdbc)),
                new InstrumentRepository(jdbc),
                transactionRepository,
                holdingRepository,
                new ValuationSnapshotService(new ValuationSnapshotRepository(jdbc), Clock.systemUTC()),
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
    }

    /** Nothing rolls back here, so every row this class writes has to be removed by hand. */
    @AfterEach
    void tearDown() {
        deleteEverything();
    }

    private void deleteEverything() {
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");
    }

    /**
     * The named case: two threads POST a BUY of 1 AAPL to the same portfolio at the same
     * instant. <b>The final quantity is 2, never 1.</b>
     */
    @Test
    void shouldNotLoseUpdateWhenTwoBuysCommitSimultaneously() throws Exception {
        runSimultaneously(
                () -> service.record(userId, portfolioId, buy("1", "100.00")),
                () -> service.record(userId, portfolioId, buy("1", "100.00")));

        assertThat(countTxnRows()).isEqualTo(2);
        List<Holding> holdings = holdingRepository.findByPortfolio(portfolioId);
        assertThat(holdings).hasSize(1);
        assertThat(holdings.get(0).quantity())
                .as("both BUYs must be reflected; 1 means the second write clobbered the first")
                .isEqualByComparingTo("2.000000");
    }

    /**
     * TEST_PLAN.md §4.8's second bullet. A BUY and a DELETE of a <i>different</i> transaction
     * race; both commit orderings are legal, so the assertion is order-independent: whatever
     * {@code txn} rows survive, the {@code holding} row must be exactly what replaying those
     * rows through {@link ProjectionEngine} produces. That rules out the inconsistent third
     * state — a projection matching neither ordering — without pinning down which one won.
     */
    @Test
    void shouldLeaveProjectionConsistentWhenABuyAndADeleteRaceOnTheSamePortfolio() throws Exception {
        transactionRepository.insert(txn(TransactionType.BUY, "10", "100.00", "2026-01-01T00:00:00Z"));
        long second = transactionRepository.insert(txn(TransactionType.BUY, "5", "110.00", "2026-01-02T00:00:00Z"));
        seedProjectionFromCurrentHistory();

        runSimultaneously(
                () -> service.record(userId, portfolioId, buy("3", "120.00")),
                () -> {
                    service.delete(userId, portfolioId, second);
                    return null;
                });

        List<Txn> surviving = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        HoldingState expected = ProjectionEngine
                .project(surviving, ProjectionContext.identity(CurrencyCode.USD))
                .holdingsByInstrument().get(aaplId);

        List<Holding> actual = holdingRepository.findByPortfolio(portfolioId);
        assertThat(actual).hasSize(1);
        assertThat(actual.get(0).quantity()).isEqualByComparingTo(expected.quantity());
        assertThat(actual.get(0).avgCost()).isEqualByComparingTo(expected.avgCost());
        assertThat(actual.get(0).realisedPnl()).isEqualByComparingTo(expected.realisedPnl());
    }

    /**
     * TEST_PLAN.md §4.8's last bullet: both threads finish inside 5 seconds. A lock-ordering
     * mistake between {@code doRecord} and {@code doDelete} shows up as a deadlock or a
     * lock-wait timeout, and either way this fails rather than hangs the suite.
     */
    @Test
    void shouldNotDeadlockWhenABuyAndADeleteContendOnTheSamePortfolio() throws Exception {
        long first = transactionRepository.insert(txn(TransactionType.BUY, "10", "100.00", "2026-01-01T00:00:00Z"));
        seedProjectionFromCurrentHistory();

        Instant startedAt = Instant.now();
        runSimultaneously(
                () -> service.record(userId, portfolioId, buy("2", "130.00")),
                () -> {
                    service.delete(userId, portfolioId, first);
                    return null;
                });

        assertThat(Duration.between(startedAt, Instant.now())).isLessThan(NO_DEADLOCK_BUDGET);
    }

    /**
     * Starts both tasks, holds them at a {@link CountDownLatch} until both are ready, then
     * releases them together. Each {@link Future#get} carries the {@link #NO_DEADLOCK_BUDGET}
     * timeout, so a deadlock fails the test instead of hanging the build.
     */
    private void runSimultaneously(Callable<?> first, Callable<?> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<?> task : List.of(first, second)) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }

            assertThat(ready.await(10, TimeUnit.SECONDS)).as("both threads reached the start gate").isTrue();
            go.countDown();

            for (Future<?> future : futures) {
                // Propagates whatever either thread threw, rather than silently passing.
                future.get(NO_DEADLOCK_BUDGET.toSeconds(), TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private RecordTransactionCommand buy(String qty, String price) {
        return new RecordTransactionCommand("AAPL", TransactionType.BUY, new BigDecimal(qty),
                new BigDecimal(price), BigDecimal.ZERO, CurrencyCode.USD,
                Instant.parse("2026-01-03T00:00:00Z"), null);
    }

    private Txn txn(TransactionType type, String qty, String price, String executedAt) {
        return new Txn(null, portfolioId, aaplId, type, new BigDecimal(qty), new BigDecimal(price),
                BigDecimal.ZERO, CurrencyCode.USD, Instant.parse(executedAt), null, null);
    }

    private void seedProjectionFromCurrentHistory() {
        List<Txn> history = transactionRepository.findByPortfolioOrderByExecutedAt(portfolioId);
        ProjectionEngine.project(history, ProjectionContext.identity(CurrencyCode.USD))
                .holdingsByInstrument().values()
                .forEach(state -> holdingRepository.upsert(portfolioId, state));
    }

    private long countTxnRows() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM txn WHERE portfolio_id = :portfolioId",
                Map.of("portfolioId", portfolioId), Long.class);
        return count == null ? 0 : count;
    }
}
