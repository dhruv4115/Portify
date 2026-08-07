package com.protify.portfolio.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.support.AbstractIntegrationTest;
import com.protify.portfolio.support.Page;
import com.protify.portfolio.support.Pageable;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class TransactionRepositoryIT extends AbstractIntegrationTest {

    private TransactionRepository repository;
    private NamedParameterJdbcTemplate jdbc;
    private long portfolioId;
    private long aaplId;
    private long relianceId;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");
        repository = new TransactionRepository(jdbc);

        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES ('sub-1', 'u1@example.com')", Map.of());
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = 'sub-1'", Map.of(), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Test', 'USD')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId",
                Map.of("userId", userId), Long.class);

        aaplId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'AAPL'", Map.of(), Long.class);
        relianceId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'RELIANCE'", Map.of(), Long.class);
    }

    private Txn buy(Long instrumentId, String executedAt) {
        return new Txn(null, portfolioId, instrumentId, TransactionType.BUY,
                new BigDecimal("10.000000"), new BigDecimal("100.0000"), new BigDecimal("0"),
                CurrencyCode.USD, Instant.parse(executedAt), null, null);
    }

    @Test
    void insertShouldReturnGeneratedId() {
        long id = repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));

        assertThat(id).isPositive();
    }

    @Test
    void orderingShouldBeStableUnderEqualTimestamps() {
        long first = repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        long second = repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        long third = repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));

        List<Txn> ordered = repository.findByPortfolioOrderByExecutedAt(portfolioId);

        assertThat(ordered).extracting(Txn::id).containsExactly(first, second, third);
    }

    @Test
    void filtersAndPagingShouldWork() {
        repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        repository.insert(buy(aaplId, "2026-01-05T00:00:00Z"));
        repository.insert(buy(relianceId, "2026-01-10T00:00:00Z"));
        Txn deposit = new Txn(null, portfolioId, null, TransactionType.DEPOSIT,
                BigDecimal.ZERO, new BigDecimal("500.0000"), BigDecimal.ZERO,
                CurrencyCode.USD, Instant.parse("2026-01-02T00:00:00Z"), null, null);
        repository.insert(deposit);

        // filter by symbol
        Page<Txn> aaplOnly = repository.findByPortfolioFiltered(portfolioId,
                new TxnFilter(null, "AAPL", null, null), new Pageable(0, 20));
        assertThat(aaplOnly.totalElements()).isEqualTo(2);

        // filter by type
        Page<Txn> deposits = repository.findByPortfolioFiltered(portfolioId,
                new TxnFilter(TransactionType.DEPOSIT, null, null, null), new Pageable(0, 20));
        assertThat(deposits.totalElements()).isEqualTo(1);

        // filter by date range
        Page<Txn> ranged = repository.findByPortfolioFiltered(portfolioId,
                new TxnFilter(null, null, LocalDate.parse("2026-01-03"), LocalDate.parse("2026-01-10")),
                new Pageable(0, 20));
        // 01-05 AAPL and 01-10 RELIANCE fall in range; 01-01 AAPL and 01-02 DEPOSIT don't.
        assertThat(ranged.totalElements()).isEqualTo(2);

        // paging
        Page<Txn> firstPage = repository.findByPortfolioFiltered(portfolioId, TxnFilter.none(), new Pageable(0, 2));
        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(4);
        assertThat(firstPage.totalPages()).isEqualTo(2);

        Page<Txn> secondPage = repository.findByPortfolioFiltered(portfolioId, TxnFilter.none(), new Pageable(1, 2));
        assertThat(secondPage.content()).hasSize(2);
    }

    /**
     * {@code q} spans symbol, instrument name and note — the three things visible on a
     * transaction row, so a search box over the table matches what the reader can see.
     */
    @Test
    void freeTextSearchShouldSpanSymbolNameAndNote() {
        repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        repository.insert(withNote(relianceId, "2026-01-05T00:00:00Z", "Post-results add"));

        // symbol
        assertThat(search("AAPL").totalElements()).isEqualTo(1);
        // instrument name — "Apple Inc." is not the symbol
        assertThat(search("apple").totalElements()).isEqualTo(1);
        // note
        assertThat(search("post-results").totalElements()).isEqualTo(1);
        // nothing at all is a legitimate answer, not an error
        assertThat(search("nonexistent").totalElements()).isZero();
    }

    @Test
    void freeTextSearchShouldBeCaseInsensitive() {
        repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));

        assertThat(search("aapl").totalElements()).isEqualTo(1);
        assertThat(search("AaPl").totalElements()).isEqualTo(1);
    }

    /**
     * The escaping rule. A stored {@code %} must be matchable literally, and — far more
     * importantly — a searched-for {@code %} must not turn into a match-anything wildcard that
     * silently returns every row in the portfolio.
     */
    @Test
    void freeTextSearchShouldTreatWildcardsAsLiteralCharacters() {
        repository.insert(withNote(aaplId, "2026-01-01T00:00:00Z", "Trimmed 50% of position"));
        repository.insert(withNote(relianceId, "2026-01-02T00:00:00Z", "Ordinary add"));

        assertThat(search("50%").totalElements()).isEqualTo(1);
        // The dangerous case: a bare % must match nothing here, never both rows.
        assertThat(search("%").totalElements()).isEqualTo(1);
        assertThat(search("_").totalElements()).isZero();
    }

    @Test
    void freeTextSearchShouldComposeWithTheOtherFilters() {
        repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        Txn deposit = new Txn(null, portfolioId, null, TransactionType.DEPOSIT,
                BigDecimal.ZERO, new BigDecimal("500.0000"), BigDecimal.ZERO,
                CurrencyCode.USD, Instant.parse("2026-01-02T00:00:00Z"), "AAPL top-up", null);
        repository.insert(deposit);

        // Both rows mention AAPL; only one is a BUY. Filters narrow together, never widen.
        assertThat(search("aapl").totalElements()).isEqualTo(2);
        Page<Txn> buysOnly = repository.findByPortfolioFiltered(portfolioId,
                new TxnFilter(TransactionType.BUY, null, null, null, "aapl"), new Pageable(0, 20));
        assertThat(buysOnly.totalElements()).isEqualTo(1);
    }

    private Page<Txn> search(String q) {
        return repository.findByPortfolioFiltered(portfolioId,
                new TxnFilter(null, null, null, null, q), new Pageable(0, 20));
    }

    private Txn withNote(Long instrumentId, String executedAt, String note) {
        return new Txn(null, portfolioId, instrumentId, TransactionType.BUY,
                new BigDecimal("10.000000"), new BigDecimal("100.0000"), new BigDecimal("0"),
                CurrencyCode.USD, Instant.parse(executedAt), note, null);
    }

    @Test
    void deleteByIdAndPortfolioShouldRemoveOnlyThatTransaction() {
        long keep = repository.insert(buy(aaplId, "2026-01-01T00:00:00Z"));
        long remove = repository.insert(buy(aaplId, "2026-01-02T00:00:00Z"));

        repository.deleteByIdAndPortfolio(remove, portfolioId);

        List<Txn> remaining = repository.findByPortfolioOrderByExecutedAt(portfolioId);
        assertThat(remaining).extracting(Txn::id).containsExactly(keep);
    }
}
