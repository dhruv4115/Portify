package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.PriceSource;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/** Hand-written {@link RowMapper} for {@code price_history} — no JPA, no Hibernate. */
class PriceHistoryRowMapper implements RowMapper<PriceHistoryRow> {

    @Override
    public PriceHistoryRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        // Never valueOf() an unrecognised source deep inside a RowMapper (CLAUDE.md) — fall
        // back to MANUAL, which is the closest honest label for "we don't recognise this source".
        PriceSource source = PriceSource.fromDbValue(rs.getString("source")).orElse(PriceSource.MANUAL);
        return new PriceHistoryRow(
                rs.getLong("instrument_id"),
                rs.getDate("price_date").toLocalDate(),
                rs.getBigDecimal("close_price"),
                source);
    }
}
