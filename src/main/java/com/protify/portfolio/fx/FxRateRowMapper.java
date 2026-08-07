package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Hand-written {@link RowMapper} for {@code fx_rate} — no JPA, no Hibernate. */
class FxRateRowMapper implements RowMapper<FxRateRow> {

    @Override
    public FxRateRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        CurrencyCode base = CurrencyCode.fromDbValue(rs.getString("base_ccy")).orElse(CurrencyCode.USD);
        CurrencyCode quote = CurrencyCode.fromDbValue(rs.getString("quote_ccy")).orElse(CurrencyCode.USD);
        FxSource source = FxSource.fromDbValue(rs.getString("source")).orElse(FxSource.MANUAL);
        return new FxRateRow(base, quote, rs.getDate("rate_date").toLocalDate(), rs.getBigDecimal("rate"), source);
    }
}
