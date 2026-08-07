package com.protify.portfolio.portfolio;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.support.BaseRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every method takes {@code userId} first and every statement filters by it
 * (CLAUDE.md non-negotiable #3) — a method that *could* return another user's row is a bug
 * even if nothing calls it that way today.
 *
 * <p>{@link #create} does not pre-check for a duplicate name with a {@code SELECT}, which
 * races; it relies on the {@code uk_portfolio_user_name} constraint and lets Spring's
 * automatic exception translation surface a {@link org.springframework.dao.DuplicateKeyException}
 * to the caller.
 */
@Repository
public class PortfolioRepository extends BaseRepository {

    public PortfolioRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public List<Portfolio> findAllByUser(long userId) {
        String sql = "SELECT * FROM portfolio WHERE user_id = :userId ORDER BY name";
        return jdbcTemplate.query(sql, new MapSqlParameterSource("userId", userId), new PortfolioRowMapper());
    }

    /**
     * <b>The one query in this class that is not scoped to a user, and the only one that may
     * ever be.</b> The nightly valuation-snapshot job (day-5-dev-A.md D5-A2) has no
     * authenticated user to scope by — it materialises yesterday for everybody.
     *
     * <p>Two things keep that safe and are the reason this is a separate, loudly named method
     * rather than a {@code findAll}: it is unreachable from any controller (no service exposed to
     * the web layer calls it), and it returns each portfolio's <b>owning {@code user_id}</b>, so
     * the job re-enters the normal read path through {@code PortfolioService.getOrThrow} as that
     * user instead of bypassing the ownership check. A method that returned rows with the owner
     * stripped off would be the bug CLAUDE.md non-negotiable #3 is about.
     */
    public List<Portfolio> findAllForSnapshotJob() {
        return jdbcTemplate.query("SELECT * FROM portfolio ORDER BY id",
                new MapSqlParameterSource(), new PortfolioRowMapper());
    }

    public Optional<Portfolio> findByIdAndUser(long userId, long portfolioId) {
        String sql = "SELECT * FROM portfolio WHERE id = :id AND user_id = :userId";
        var params = new MapSqlParameterSource().addValue("id", portfolioId).addValue("userId", userId);
        List<Portfolio> results = jdbcTemplate.query(sql, params, new PortfolioRowMapper());
        return results.stream().findFirst();
    }

    public Portfolio create(long userId, String name, CurrencyCode baseCurrency) {
        String sql = """
                INSERT INTO portfolio (user_id, name, base_currency)
                VALUES (:userId, :name, :baseCurrency)
                """;
        var params = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("name", name)
                .addValue("baseCurrency", baseCurrency.name());
        long id = insert(sql, params);
        return findByIdAndUser(userId, id)
                .orElseThrow(() -> new IllegalStateException("Portfolio not found immediately after insert: " + id));
    }

    /**
     * API_CONTRACT.md §7 — "cascades to its transactions, holdings and valuation snapshots".
     * The snapshot delete is not optional politeness: once D5-A2 started materialising
     * {@code portfolio_valuation_daily}, {@code fk_val_portfolio} makes the final
     * {@code DELETE FROM portfolio} fail outright without it.
     */
    @Transactional
    public void deleteByIdAndUser(long userId, long portfolioId) {
        var params = new MapSqlParameterSource().addValue("id", portfolioId).addValue("userId", userId);

        jdbcTemplate.update("""
                DELETE FROM portfolio_valuation_daily
                WHERE portfolio_id IN (SELECT id FROM portfolio WHERE id = :id AND user_id = :userId)
                """, params);
        jdbcTemplate.update("""
                DELETE FROM holding
                WHERE portfolio_id IN (SELECT id FROM portfolio WHERE id = :id AND user_id = :userId)
                """, params);
        jdbcTemplate.update("""
                DELETE FROM txn
                WHERE portfolio_id IN (SELECT id FROM portfolio WHERE id = :id AND user_id = :userId)
                """, params);
        jdbcTemplate.update("DELETE FROM portfolio WHERE id = :id AND user_id = :userId", params);
    }

    /** {@code SELECT ... FOR UPDATE} — must run inside an active transaction; serialises writes
     * to one portfolio without contending with writes to a different one (ADR-0002). */
    public Optional<Portfolio> lockForUpdate(long userId, long portfolioId) {
        String sql = "SELECT * FROM portfolio WHERE id = :id AND user_id = :userId FOR UPDATE";
        var params = new MapSqlParameterSource().addValue("id", portfolioId).addValue("userId", userId);
        try {
            return Optional.of(jdbcTemplate.queryForObject(sql, params, new PortfolioRowMapper()));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * day-4-dev-C.md D4-C4 (API_CONTRACT.md §5). Writes only {@code name} and
     * {@code base_currency} on the {@code portfolio} row itself — never {@code txn}, never
     * {@code holding}. That is the whole point of {@code BaseCurrencyImmutabilityIT}: changing
     * the base currency is a presentation concern, and this method is mechanically incapable of
     * touching the tables that would make it anything else.
     */
    public Portfolio update(long userId, long portfolioId, String name, CurrencyCode baseCurrency) {
        String sql = """
                UPDATE portfolio SET name = :name, base_currency = :baseCurrency
                WHERE id = :id AND user_id = :userId
                """;
        var params = new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("baseCurrency", baseCurrency.name())
                .addValue("id", portfolioId)
                .addValue("userId", userId);
        jdbcTemplate.update(sql, params);
        return findByIdAndUser(userId, portfolioId)
                .orElseThrow(() -> new IllegalStateException(
                        "Portfolio not found immediately after update: " + portfolioId));
    }
}
