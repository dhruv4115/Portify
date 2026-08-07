package com.protify.portfolio.portfolio;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public final class PortfolioRowMapper implements RowMapper<Portfolio> {

    @Override
    public Portfolio mapRow(ResultSet rs, int rowNum) throws SQLException {
        String baseCurrencyValue = rs.getString("base_currency");
        CurrencyCode baseCurrency = CurrencyCode.fromDbValue(baseCurrencyValue)
                .orElseThrow(() -> new IllegalStateException("Unrecognised base_currency: " + baseCurrencyValue));

        return new Portfolio(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("name"),
                baseCurrency,
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
