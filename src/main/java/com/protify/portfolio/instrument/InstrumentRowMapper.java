package com.protify.portfolio.instrument;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

public final class InstrumentRowMapper implements RowMapper<Instrument> {

    @Override
    public Instrument mapRow(ResultSet rs, int rowNum) throws SQLException {
        String assetTypeValue = rs.getString("asset_type");
        AssetType assetType = AssetType.fromDbValue(assetTypeValue)
                .orElseThrow(() -> new IllegalStateException("Unrecognised asset_type: " + assetTypeValue));

        String currencyValue = rs.getString("currency");
        CurrencyCode currency = CurrencyCode.fromDbValue(currencyValue)
                .orElseThrow(() -> new IllegalStateException("Unrecognised currency: " + currencyValue));

        return new Instrument(
                rs.getLong("id"),
                rs.getString("symbol"),
                rs.getString("name"),
                assetType,
                currency,
                rs.getString("exchange"),
                rs.getString("sector"),
                rs.getTimestamp("created_at").toInstant());
    }
}
