package com.protify.portfolio.holding;

import static org.assertj.core.api.Assertions.assertThat;

import com.protify.portfolio.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class HoldingRepositoryIT extends AbstractIntegrationTest {

    private HoldingRepository repository;
    private NamedParameterJdbcTemplate jdbc;
    private long portfolioA;
    private long portfolioB;
    private long aaplId;
    private long msftId;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");
        repository = new HoldingRepository(jdbc);

        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES ('sub-1', 'u1@example.com')", Map.of());
        Long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = 'sub-1'", Map.of(), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'A', 'USD')",
                Map.of("userId", userId));
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'B', 'USD')",
                Map.of("userId", userId));
        portfolioA = jdbc.queryForObject("SELECT id FROM portfolio WHERE name = 'A'", Map.of(), Long.class);
        portfolioB = jdbc.queryForObject("SELECT id FROM portfolio WHERE name = 'B'", Map.of(), Long.class);

        aaplId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'AAPL'", Map.of(), Long.class);
        msftId = jdbc.queryForObject("SELECT id FROM instrument WHERE symbol = 'MSFT'", Map.of(), Long.class);
    }

    @Test
    void upsertShouldInsertThenUpdate() {
        repository.upsert(portfolioA, new HoldingState(aaplId, new BigDecimal("10.000000"),
                new BigDecimal("100.0000"), new BigDecimal("0.0000")));

        List<Holding> afterInsert = repository.findByPortfolio(portfolioA);
        assertThat(afterInsert).hasSize(1);
        assertThat(afterInsert.get(0).quantity()).isEqualByComparingTo("10.000000");

        repository.upsert(portfolioA, new HoldingState(aaplId, new BigDecimal("15.000000"),
                new BigDecimal("120.0000"), new BigDecimal("5.0000")));

        List<Holding> afterUpdate = repository.findByPortfolio(portfolioA);
        assertThat(afterUpdate).hasSize(1);
        assertThat(afterUpdate.get(0).quantity()).isEqualByComparingTo("15.000000");
        assertThat(afterUpdate.get(0).avgCost()).isEqualByComparingTo("120.0000");
        assertThat(afterUpdate.get(0).realisedPnl()).isEqualByComparingTo("5.0000");
    }

    @Test
    void upsertShouldBeIdempotentUnderRepeat() {
        HoldingState state = new HoldingState(aaplId, new BigDecimal("10.000000"),
                new BigDecimal("100.0000"), new BigDecimal("0.0000"));

        repository.upsert(portfolioA, state);
        repository.upsert(portfolioA, state);
        repository.upsert(portfolioA, state);

        assertThat(repository.findByPortfolio(portfolioA)).hasSize(1);
    }

    @Test
    void deleteAllForPortfolioShouldClearExactlyOnePortfolio() {
        repository.upsert(portfolioA, new HoldingState(aaplId, new BigDecimal("10.000000"),
                new BigDecimal("100.0000"), new BigDecimal("0.0000")));
        repository.upsert(portfolioA, new HoldingState(msftId, new BigDecimal("5.000000"),
                new BigDecimal("40.0000"), new BigDecimal("0.0000")));
        repository.upsert(portfolioB, new HoldingState(aaplId, new BigDecimal("2.000000"),
                new BigDecimal("50.0000"), new BigDecimal("0.0000")));

        repository.deleteAllForPortfolio(portfolioA);

        assertThat(repository.findByPortfolio(portfolioA)).isEmpty();
        assertThat(repository.findByPortfolio(portfolioB)).hasSize(1);
    }
}
