package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import org.springframework.jdbc.core.RowMapper;

/** Hand-written, like every other {@code *RowMapper} here — no JPA, no reflection. */
public final class ValuationSnapshotRowMapper implements RowMapper<ValuationSnapshot> {

    @Override
    public ValuationSnapshot mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ValuationSnapshot(
                rs.getLong("portfolio_id"),
                rs.getDate("valuation_date").toLocalDate(),
                // A row whose currency no longer maps to a known enum constant is skipped by the
                // repository rather than defaulted — serving a GBP valuation as USD would be a
                // far worse answer than recomputing the day (CLAUDE.md: unknown enum values are
                // handled explicitly).
                CurrencyCode.fromDbValue(rs.getString("currency")).orElse(null),
                rs.getBigDecimal("market_value"),
                rs.getBigDecimal("cost_basis"),
                rs.getBigDecimal("cash_balance"),
                rs.getBigDecimal("unrealised_pnl"),
                rs.getBoolean("filled"),
                toLocalDate(rs.getDate("price_as_of")),
                toLocalDate(rs.getDate("rate_as_of")));
    }

    /** {@code price_as_of}/{@code rate_as_of} are null for a day with no priced position — a
     * cash-only portfolio, or one whose every holding was sold down to zero. */
    private static LocalDate toLocalDate(Date date) {
        return date == null ? null : date.toLocalDate();
    }
}
