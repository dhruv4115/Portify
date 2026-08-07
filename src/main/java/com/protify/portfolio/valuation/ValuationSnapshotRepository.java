package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.support.BaseRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code portfolio_valuation_daily} — day-5-dev-A.md D5-A2.
 *
 * <p>Scoped by {@code portfolioId}, which every caller has already resolved through
 * {@code PortfolioService.getOrThrow} or {@code lockForUpdate}, exactly like
 * {@code HoldingRepository}. Nothing here reads a portfolio the caller did not already prove
 * they own.
 */
@Repository
public class ValuationSnapshotRepository extends BaseRepository {

    private static final ValuationSnapshotRowMapper ROW_MAPPER = new ValuationSnapshotRowMapper();

    public ValuationSnapshotRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    /**
     * Snapshots for {@code [from, to]} inclusive, keyed by date. A row whose stored currency no
     * longer maps to a {@link CurrencyCode} is dropped rather than served, which leaves that day
     * looking uncovered — so it gets recomputed, which is the safe direction to fail in.
     */
    public NavigableMap<LocalDate, ValuationSnapshot> findRange(
            long portfolioId, CurrencyCode currency, LocalDate from, LocalDate to) {

        String sql = """
                SELECT portfolio_id, valuation_date, currency, market_value, cost_basis,
                       cash_balance, unrealised_pnl, filled, price_as_of, rate_as_of
                FROM portfolio_valuation_daily
                WHERE portfolio_id = :portfolioId
                  AND currency = :currency
                  AND valuation_date BETWEEN :from AND :to
                """;
        var params = new MapSqlParameterSource()
                .addValue("portfolioId", portfolioId)
                .addValue("currency", currency.name())
                .addValue("from", from)
                .addValue("to", to);

        NavigableMap<LocalDate, ValuationSnapshot> byDate = new TreeMap<>();
        for (ValuationSnapshot snapshot : jdbcTemplate.query(sql, params, ROW_MAPPER)) {
            if (snapshot.currency() != null) {
                byDate.put(snapshot.date(), snapshot);
            }
        }
        return byDate;
    }

    /** Idempotent on the {@code uk_val} key, so a concurrent read-through write and the nightly
     * job cannot collide into a duplicate-key failure. */
    public void upsert(ValuationSnapshot snapshot) {
        String sql = """
                INSERT INTO portfolio_valuation_daily
                    (portfolio_id, valuation_date, currency, market_value, cost_basis,
                     cash_balance, unrealised_pnl, filled, price_as_of, rate_as_of)
                VALUES
                    (:portfolioId, :valuationDate, :currency, :marketValue, :costBasis,
                     :cashBalance, :unrealisedPnl, :filled, :priceAsOf, :rateAsOf)
                ON DUPLICATE KEY UPDATE
                    market_value   = VALUES(market_value),
                    cost_basis     = VALUES(cost_basis),
                    cash_balance   = VALUES(cash_balance),
                    unrealised_pnl = VALUES(unrealised_pnl),
                    filled         = VALUES(filled),
                    price_as_of    = VALUES(price_as_of),
                    rate_as_of     = VALUES(rate_as_of)
                """;
        upsert(sql, params(snapshot));
    }

    public void upsertAll(List<ValuationSnapshot> snapshots) {
        for (ValuationSnapshot snapshot : snapshots) {
            upsert(snapshot);
        }
    }

    /**
     * Every snapshot on or after {@code from}, for <b>every</b> currency.
     *
     * <p>A transaction inserted or deleted mid-history changes that day and every day after it,
     * in every currency the portfolio has ever been charted in — so the invalidation is not
     * scoped by currency, and it is not scoped to a single date either. A stale snapshot is a
     * wrong chart (day-5-dev-A.md D5-A2).
     */
    public int deleteFrom(long portfolioId, LocalDate from) {
        String sql = """
                DELETE FROM portfolio_valuation_daily
                WHERE portfolio_id = :portfolioId AND valuation_date >= :from
                """;
        var params = new MapSqlParameterSource()
                .addValue("portfolioId", portfolioId)
                .addValue("from", from);
        return jdbcTemplate.update(sql, params);
    }

    public int deleteAllForPortfolio(long portfolioId) {
        return jdbcTemplate.update("DELETE FROM portfolio_valuation_daily WHERE portfolio_id = :portfolioId",
                new MapSqlParameterSource("portfolioId", portfolioId));
    }

    private static MapSqlParameterSource params(ValuationSnapshot snapshot) {
        return new MapSqlParameterSource()
                .addValue("portfolioId", snapshot.portfolioId())
                .addValue("valuationDate", snapshot.date())
                .addValue("currency", snapshot.currency().name())
                .addValue("marketValue", snapshot.marketValue())
                .addValue("costBasis", snapshot.costBasis())
                .addValue("cashBalance", snapshot.cashBalance())
                .addValue("unrealisedPnl", snapshot.unrealisedPnl())
                .addValue("filled", snapshot.filled())
                .addValue("priceAsOf", snapshot.priceAsOf())
                .addValue("rateAsOf", snapshot.rateAsOf());
    }
}
