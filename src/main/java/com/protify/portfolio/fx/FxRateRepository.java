package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.support.BaseRepository;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * D2-B3 — {@code fx_rate} stores one row per currency per day against a **USD pivot**
 * (ADR-0011): {@code base_ccy} is always {@code USD}, {@code quote_ccy} the other currency.
 * Storing pairs directly would be O(n²) rows for no gain. Upsert via
 * {@code INSERT ... ON DUPLICATE KEY UPDATE} on {@code (base_ccy, quote_ccy, rate_date)},
 * through {@link BaseRepository}.
 */
@Repository
public class FxRateRepository extends BaseRepository {

    private static final FxRateRowMapper ROW_MAPPER = new FxRateRowMapper();

    private final NamedParameterJdbcTemplate jdbc;

    public FxRateRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
        this.jdbc = jdbcTemplate;
    }

    /** Most recent row on or before {@code onOrBefore} for the given pivot pair — never a
     * future rate (a future rate invents history). */
    public Optional<FxRateRow> mostRecentOnOrBefore(CurrencyCode base, CurrencyCode quote, LocalDate onOrBefore) {
        String sql = """
                SELECT base_ccy, quote_ccy, rate_date, rate, source
                FROM fx_rate
                WHERE base_ccy = :base AND quote_ccy = :quote AND rate_date <= :onOrBefore
                ORDER BY rate_date DESC
                LIMIT 1
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("base", base.name())
                .addValue("quote", quote.name())
                .addValue("onOrBefore", onOrBefore);
        return jdbc.query(sql, params, ROW_MAPPER).stream().findFirst();
    }

    /** D3-B3 — exact-date lookup, distinct from {@link #mostRecentOnOrBefore}: used to decide
     * whether a date has ever actually been fetched, so a provider is called at most once per
     * date rather than on every cache expiry. */
    public Optional<FxRateRow> findExact(CurrencyCode base, CurrencyCode quote, LocalDate date) {
        String sql = """
                SELECT base_ccy, quote_ccy, rate_date, rate, source
                FROM fx_rate
                WHERE base_ccy = :base AND quote_ccy = :quote AND rate_date = :date
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("base", base.name())
                .addValue("quote", quote.name())
                .addValue("date", date);
        return jdbc.query(sql, params, ROW_MAPPER).stream().findFirst();
    }

    /** D3-B4 — {@code FxHealthIndicator} needs only the date of the most recent row, not the
     * full (package-private) {@link FxRateRow} — this is the public-facing shape a health
     * indicator in another package can actually call. */
    public Optional<LocalDate> mostRecentDateOnOrBefore(CurrencyCode base, CurrencyCode quote, LocalDate onOrBefore) {
        return mostRecentOnOrBefore(base, quote, onOrBefore).map(FxRateRow::rateDate);
    }

    /**
     * ADR-0010 — one of the "three flat queries" {@code PerformanceService} folds in memory:
     * every stored (USD-pivot) rate up to {@code to}, grouped by date. No lower bound, for the
     * same reason as {@link com.protify.portfolio.marketdata.PriceHistoryRepository#findRange}
     * — the fold needs the row before the range to forward-fill its first day. {@code USD}
     * itself never appears as a {@code quoteCcy} key since it is never stored against itself
     * (rate is always 1 by definition); callers treat a {@code USD} lookup as 1 directly.
     */
    public NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> findRange(LocalDate to) {
        NavigableMap<LocalDate, Map<CurrencyCode, BigDecimal>> result = new TreeMap<>();
        String sql = """
                SELECT quote_ccy, rate_date, rate
                FROM fx_rate
                WHERE base_ccy = 'USD' AND rate_date <= :to
                """;
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("to", to);
        jdbc.query(sql, params, rs -> {
            CurrencyCode quote = CurrencyCode.fromDbValue(rs.getString("quote_ccy")).orElse(null);
            if (quote == null) {
                return;
            }
            LocalDate date = rs.getDate("rate_date").toLocalDate();
            BigDecimal rate = rs.getBigDecimal("rate");
            result.computeIfAbsent(date, k -> new HashMap<>()).put(quote, rate);
        });
        return result;
    }

    public void upsert(CurrencyCode base, CurrencyCode quote, LocalDate rateDate, BigDecimal rate, FxSource source) {
        String sql = """
                INSERT INTO fx_rate (base_ccy, quote_ccy, rate_date, rate, source)
                VALUES (:base, :quote, :rateDate, :rate, :source)
                ON DUPLICATE KEY UPDATE
                    rate = VALUES(rate),
                    source = VALUES(source),
                    fetched_at = CURRENT_TIMESTAMP(6)
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("base", base.name())
                .addValue("quote", quote.name())
                .addValue("rateDate", rateDate)
                .addValue("rate", rate)
                .addValue("source", source.name());
        upsert(sql, params);
    }
}
