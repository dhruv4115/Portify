package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * D2-B2 — read-only lookup of {@code instrument.symbol}/{@code currency} by id, needed because
 * {@link com.protify.portfolio.instrument.InstrumentRepository} (Dev A's, not to be edited —
 * CLAUDE.md) exposes {@code findBySymbol}/{@code search} but not {@code findById}, and
 * {@code CachingMarketDataService.priceFor(instrumentId, date)} (the shape Dev A calls
 * tomorrow) is id-first. A separate, narrowly-scoped repository here — never a raw
 * {@code JdbcTemplate} call outside one (CLAUDE.md non-negotiable #6) — rather than adding a
 * method to another developer's class.
 */
@Repository
class InstrumentLookupRepository {

    private final NamedParameterJdbcTemplate jdbc;

    InstrumentLookupRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record InstrumentRef(long id, String symbol, CurrencyCode currency) {
    }

    Optional<InstrumentRef> findById(long instrumentId) {
        String sql = "SELECT id, symbol, currency FROM instrument WHERE id = :id";
        MapSqlParameterSource params = new MapSqlParameterSource("id", instrumentId);
        return jdbc.query(sql, params, (rs, rowNum) -> new InstrumentRef(
                        rs.getLong("id"),
                        rs.getString("symbol"),
                        CurrencyCode.valueOf(rs.getString("currency"))))
                .stream()
                .findFirst();
    }

    /** D3-B1 — every seeded instrument, the default scope of a scheduled or admin-triggered
     * price refresh when no explicit symbol list is given. */
    List<InstrumentRef> findAll() {
        String sql = "SELECT id, symbol, currency FROM instrument ORDER BY id";
        return jdbc.query(sql, (rs, rowNum) -> new InstrumentRef(
                rs.getLong("id"),
                rs.getString("symbol"),
                CurrencyCode.valueOf(rs.getString("currency"))));
    }
}
