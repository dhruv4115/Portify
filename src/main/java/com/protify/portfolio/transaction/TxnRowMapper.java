package com.protify.portfolio.transaction;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.TransactionType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.jdbc.core.RowMapper;

public final class TxnRowMapper implements RowMapper<Txn> {

    @Override
    public Txn mapRow(ResultSet rs, int rowNum) throws SQLException {
        String txnTypeValue = rs.getString("txn_type");
        TransactionType txnType = TransactionType.fromDbValue(txnTypeValue)
                .orElseThrow(() -> new IllegalStateException("Unrecognised txn_type: " + txnTypeValue));

        String currencyValue = rs.getString("currency");
        CurrencyCode currency = CurrencyCode.fromDbValue(currencyValue)
                .orElseThrow(() -> new IllegalStateException("Unrecognised currency: " + currencyValue));

        long instrumentId = rs.getLong("instrument_id");
        Long instrumentIdOrNull = rs.wasNull() ? null : instrumentId;
        Timestamp createdAt = rs.getTimestamp("created_at");

        return new Txn(
                rs.getLong("id"),
                rs.getLong("portfolio_id"),
                instrumentIdOrNull,
                txnType,
                rs.getBigDecimal("quantity"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("fees"),
                currency,
                rs.getTimestamp("executed_at").toInstant(),
                rs.getString("note"),
                createdAt == null ? null : createdAt.toInstant());
    }
}
