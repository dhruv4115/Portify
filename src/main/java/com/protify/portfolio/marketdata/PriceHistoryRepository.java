package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.support.BaseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * D2-B2 — explicit SQL, hand-written {@link PriceHistoryRowMapper}. Upsert via
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} on the {@code (instrument_id, price_date)} unique
 * key, through {@link BaseRepository}, so it is idempotent under a repeated fetch for the same
 * day.
 */
@Repository
public class PriceHistoryRepository extends BaseRepository {

    private static final PriceHistoryRowMapper ROW_MAPPER = new PriceHistoryRowMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public PriceHistoryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
        this.jdbc = jdbcTemplate;
    }

    /** The most recent row on or before {@code onOrBefore} — never a future row (a future price
     * would invent history). */
    public Optional<PriceHistoryRow> mostRecentOnOrBefore(long instrumentId, LocalDate onOrBefore) {
        String sql = """
                SELECT instrument_id, price_date, close_price, source
                FROM price_history
                WHERE instrument_id = :instrumentId AND price_date <= :onOrBefore
                ORDER BY price_date DESC
                LIMIT 1
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("instrumentId", instrumentId)
                .addValue("onOrBefore", onOrBefore);
        return jdbc.query(sql, params, ROW_MAPPER).stream().findFirst();
    }

    /**
     * ADR-0010 — one of the "three flat queries" {@code PerformanceService} folds in memory.
     * No lower bound on {@code price_date}: the in-memory fold needs the row immediately
     * before the requested range too, to forward-fill the range's first day correctly, and
     * fetching the whole history per instrument is the trade-off ADR-0010 explicitly accepts.
     */
    public Map<Long, NavigableMap<LocalDate, BigDecimal>> findRange(Collection<Long> instrumentIds, LocalDate to) {
        Map<Long, NavigableMap<LocalDate, BigDecimal>> result = new HashMap<>();
        if (instrumentIds.isEmpty()) {
            return result;
        }
        String sql = """
                SELECT instrument_id, price_date, close_price
                FROM price_history
                WHERE instrument_id IN (:instrumentIds) AND price_date <= :to
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("instrumentIds", instrumentIds)
                .addValue("to", to);
        jdbc.query(sql, params, rs -> {
            long instrumentId = rs.getLong("instrument_id");
            LocalDate date = rs.getDate("price_date").toLocalDate();
            BigDecimal close = rs.getBigDecimal("close_price");
            result.computeIfAbsent(instrumentId, k -> new TreeMap<>()).put(date, close);
        });
        return result;
    }

    public void upsert(long instrumentId, LocalDate priceDate, BigDecimal closePrice, PriceSource source) {
        String sql = """
                INSERT INTO price_history (instrument_id, price_date, close_price, source)
                VALUES (:instrumentId, :priceDate, :closePrice, :source)
                ON DUPLICATE KEY UPDATE
                    close_price = VALUES(close_price),
                    source = VALUES(source),
                    fetched_at = CURRENT_TIMESTAMP(6)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("instrumentId", instrumentId)
                .addValue("priceDate", priceDate)
                .addValue("closePrice", closePrice)
                .addValue("source", source.name());
        upsert(sql, params);
    }
}
