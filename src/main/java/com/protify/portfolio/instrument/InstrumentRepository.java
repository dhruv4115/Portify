package com.protify.portfolio.instrument;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.support.BaseRepository;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Not user-scoped — the instrument catalogue is shared across everyone. */
@Repository
public class InstrumentRepository extends BaseRepository {

    private static final int MAX_SEARCH_LIMIT = 50;

    public InstrumentRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    /** Case-insensitive, trimmed — {@code " aapl "} resolves to {@code AAPL}. */
    public Optional<Instrument> findBySymbol(String symbol) {
        String sql = "SELECT * FROM instrument WHERE UPPER(symbol) = UPPER(:symbol)";
        var params = new MapSqlParameterSource("symbol", symbol.strip());
        List<Instrument> results = jdbcTemplate.query(sql, params, new InstrumentRowMapper());
        return results.stream().findFirst();
    }

    /** Valuation needs the native currency of each instrument it holds, keyed by {@code
     * holding.instrument_id} — this is the lookup for that, not user-scoped like the rest of
     * the catalogue. */
    public Optional<Instrument> findById(long id) {
        String sql = "SELECT * FROM instrument WHERE id = :id";
        var params = new MapSqlParameterSource("id", id);
        List<Instrument> results = jdbcTemplate.query(sql, params, new InstrumentRowMapper());
        return results.stream().findFirst();
    }

    /**
     * The batch form of {@link #findById}, keyed by id — day-5-dev-A.md D5-A3.
     *
     * <p>Valuing a portfolio means resolving every held instrument, and doing that one
     * {@code findById} at a time is an N+1: twenty round trips where one {@code IN} does. Each of
     * those lookups is already a primary-key hit ({@code type=const} under EXPLAIN), so what this
     * saves is not index work, it is the round trips — which is why the fix is here and not in
     * {@code V4__perf_indexes.sql}.
     *
     * <p>An empty collection short-circuits: {@code IN ()} is a syntax error in MySQL, not an
     * empty result. Ids with no row are simply absent from the map, exactly as {@code findById}
     * returns empty for them.
     */
    public Map<Long, Instrument> findAllByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        String sql = "SELECT * FROM instrument WHERE id IN (:ids)";
        var params = new MapSqlParameterSource("ids", ids);
        Map<Long, Instrument> byId = new LinkedHashMap<>();
        for (Instrument instrument : jdbcTemplate.query(sql, params, new InstrumentRowMapper())) {
            byId.put(instrument.id(), instrument);
        }
        return byId;
    }

    /** Ranks symbol-prefix matches above name-substring matches, in SQL, via a {@code CASE} in
     * {@code ORDER BY} — never sorted in Java. */
    public List<Instrument> search(String query, AssetType type, CurrencyCode currency, int limit) {
        int effectiveLimit = Math.min(limit, MAX_SEARCH_LIMIT);
        String normalizedQuery = query == null ? null : query.strip();

        String sql = """
                SELECT * FROM instrument
                WHERE (:query IS NULL
                       OR symbol LIKE CONCAT(:query, '%')
                       OR name LIKE CONCAT('%', :query, '%'))
                  AND (:type IS NULL OR asset_type = :type)
                  AND (:currency IS NULL OR currency = :currency)
                ORDER BY
                  CASE WHEN symbol LIKE CONCAT(:query, '%') THEN 0 ELSE 1 END,
                  symbol
                LIMIT :limit
                """;
        var params = new MapSqlParameterSource()
                .addValue("query", normalizedQuery)
                .addValue("type", type == null ? null : type.name())
                .addValue("currency", currency == null ? null : currency.name())
                .addValue("limit", effectiveLimit);

        return jdbcTemplate.query(sql, params, new InstrumentRowMapper());
    }
}
