package com.protify.portfolio.holding;

import com.protify.portfolio.support.BaseRepository;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HoldingRepository extends BaseRepository {

    public HoldingRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public void upsert(long portfolioId, HoldingState state) {
        String sql = """
                INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
                VALUES (:portfolioId, :instrumentId, :quantity, :avgCost, :realisedPnl)
                ON DUPLICATE KEY UPDATE
                    quantity = VALUES(quantity),
                    avg_cost = VALUES(avg_cost),
                    realised_pnl = VALUES(realised_pnl)
                """;
        var params = new MapSqlParameterSource()
                .addValue("portfolioId", portfolioId)
                .addValue("instrumentId", state.instrumentId())
                .addValue("quantity", state.quantity())
                .addValue("avgCost", state.avgCost())
                .addValue("realisedPnl", state.realisedPnl());
        upsert(sql, params);
    }

    public void deleteAllForPortfolio(long portfolioId) {
        jdbcTemplate.update("DELETE FROM holding WHERE portfolio_id = :portfolioId",
                new MapSqlParameterSource("portfolioId", portfolioId));
    }

    public List<Holding> findByPortfolio(long portfolioId) {
        String sql = "SELECT * FROM holding WHERE portfolio_id = :portfolioId ORDER BY instrument_id";
        var params = new MapSqlParameterSource("portfolioId", portfolioId);
        return jdbcTemplate.query(sql, params, new HoldingRowMapper());
    }
}
