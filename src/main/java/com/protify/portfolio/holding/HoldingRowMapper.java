package com.protify.portfolio.holding;

import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public final class HoldingRowMapper implements RowMapper<Holding> {

    @Override
    public Holding mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Holding(
                rs.getLong("id"),
                rs.getLong("portfolio_id"),
                rs.getLong("instrument_id"),
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("avg_cost"),
                rs.getBigDecimal("realised_pnl"),
                rs.getTimestamp("updated_at").toInstant());
    }
}
