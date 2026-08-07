package com.protify.portfolio.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class PortfolioRepositoryIT extends AbstractIntegrationTest {

    private PortfolioRepository repository;
    private NamedParameterJdbcTemplate jdbc;
    private long userA;
    private long userB;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");
        repository = new PortfolioRepository(jdbc);
        userA = insertUser("sub-a", "a@example.com");
        userB = insertUser("sub-b", "b@example.com");
    }

    private long insertUser(String googleSub, String email) {
        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES (:googleSub, :email)",
                Map.of("googleSub", googleSub, "email", email));
        Long id = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = :googleSub",
                Map.of("googleSub", googleSub), Long.class);
        return id;
    }

    @Test
    void shouldCreateAndFindPortfolio() {
        Portfolio created = repository.create(userA, "Retirement", CurrencyCode.USD);

        assertThat(created.id()).isPositive();
        assertThat(created.userId()).isEqualTo(userA);
        assertThat(created.name()).isEqualTo("Retirement");
        assertThat(created.baseCurrency()).isEqualTo(CurrencyCode.USD);

        Optional<Portfolio> found = repository.findByIdAndUser(userA, created.id());
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("Retirement");
    }

    @Test
    void shouldListAllPortfoliosForUser() {
        repository.create(userA, "Retirement", CurrencyCode.USD);
        repository.create(userA, "Trading", CurrencyCode.INR);
        repository.create(userB, "Other user's portfolio", CurrencyCode.GBP);

        assertThat(repository.findAllByUser(userA)).hasSize(2)
                .extracting(Portfolio::name)
                .containsExactlyInAnyOrder("Retirement", "Trading");
    }

    @Test
    void shouldThrowDuplicateKeyExceptionForSameUserSameName() {
        repository.create(userA, "Retirement", CurrencyCode.USD);

        assertThatThrownBy(() -> repository.create(userA, "Retirement", CurrencyCode.USD))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void shouldAllowSameNameForDifferentUsers() {
        repository.create(userA, "Retirement", CurrencyCode.USD);

        Portfolio other = repository.create(userB, "Retirement", CurrencyCode.EUR);

        assertThat(other.id()).isPositive();
    }

    @Test
    void shouldNotReturnOtherUsersPortfolio() {
        Portfolio owned = repository.create(userA, "Retirement", CurrencyCode.USD);

        Optional<Portfolio> asOtherUser = repository.findByIdAndUser(userB, owned.id());

        assertThat(asOtherUser).isEmpty();
    }

    @Test
    void shouldLockForUpdateOnlyForOwningUser() {
        Portfolio owned = repository.create(userA, "Retirement", CurrencyCode.USD);

        assertThat(repository.lockForUpdate(userA, owned.id())).isPresent();
        assertThat(repository.lockForUpdate(userB, owned.id())).isEmpty();
    }

    @Test
    void deleteShouldCascadeToTxnAndHoldingInOneTransaction() {
        Portfolio portfolio = repository.create(userA, "Retirement", CurrencyCode.USD);
        long instrumentId = jdbc.queryForObject(
                "SELECT id FROM instrument LIMIT 1", Map.of(), Long.class);

        jdbc.update("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, :instrumentId, 'BUY', 10, 100, 0, 'USD', NOW())
                """, Map.of("portfolioId", portfolio.id(), "instrumentId", instrumentId));
        jdbc.update("""
                INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
                VALUES (:portfolioId, :instrumentId, 10, 100, 0)
                """, Map.of("portfolioId", portfolio.id(), "instrumentId", instrumentId));

        repository.deleteByIdAndUser(userA, portfolio.id());

        assertThat(repository.findByIdAndUser(userA, portfolio.id())).isEmpty();
        Integer txnCount = jdbc.queryForObject("SELECT COUNT(*) FROM txn WHERE portfolio_id = :id",
                Map.of("id", portfolio.id()), Integer.class);
        Integer holdingCount = jdbc.queryForObject("SELECT COUNT(*) FROM holding WHERE portfolio_id = :id",
                Map.of("id", portfolio.id()), Integer.class);
        assertThat(txnCount).isZero();
        assertThat(holdingCount).isZero();
    }

    @Test
    void deleteShouldNotAffectOtherUsersPortfolio() {
        Portfolio ownedByB = repository.create(userB, "Untouched", CurrencyCode.USD);

        repository.deleteByIdAndUser(userA, ownedByB.id());

        assertThat(repository.findByIdAndUser(userB, ownedByB.id())).isPresent();
    }
}
