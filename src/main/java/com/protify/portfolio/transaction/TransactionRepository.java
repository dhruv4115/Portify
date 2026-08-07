package com.protify.portfolio.transaction;

import com.protify.portfolio.support.BaseRepository;
import com.protify.portfolio.support.Page;
import com.protify.portfolio.support.Pageable;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class TransactionRepository extends BaseRepository {

    public TransactionRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    public long insert(Txn txn) {
        String sql = """
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at, note)
                VALUES (:portfolioId, :instrumentId, :txnType, :quantity, :price, :fees, :currency, :executedAt, :note)
                """;
        var params = new MapSqlParameterSource()
                .addValue("portfolioId", txn.portfolioId())
                .addValue("instrumentId", txn.instrumentId())
                .addValue("txnType", txn.txnType().name())
                .addValue("quantity", txn.quantity())
                .addValue("price", txn.price())
                .addValue("fees", txn.fees())
                .addValue("currency", txn.currency().name())
                .addValue("executedAt", Timestamp.from(txn.executedAt()))
                .addValue("note", txn.note());
        return insert(sql, params);
    }

    /** Ordered by {@code executed_at} then {@code id} — two transactions at the same instant
     * must fold deterministically, or the projection is non-reproducible. */
    public List<Txn> findByPortfolioOrderByExecutedAt(long portfolioId) {
        String sql = "SELECT * FROM txn WHERE portfolio_id = :portfolioId ORDER BY executed_at, id";
        var params = new MapSqlParameterSource("portfolioId", portfolioId);
        return jdbcTemplate.query(sql, params, new TxnRowMapper());
    }

    /** One transaction, scoped to its portfolio so a caller cannot read another portfolio's row
     * by guessing an id (CLAUDE.md non-negotiable #3). Read before a delete, because the row's
     * {@code executed_at} decides how far back the valuation cache is invalidated and it is gone
     * once the delete has run (day-5-dev-A.md D5-A2). */
    public Optional<Txn> findByIdAndPortfolio(long txnId, long portfolioId) {
        String sql = "SELECT * FROM txn WHERE id = :id AND portfolio_id = :portfolioId";
        var params = new MapSqlParameterSource().addValue("id", txnId).addValue("portfolioId", portfolioId);
        return jdbcTemplate.query(sql, params, new TxnRowMapper()).stream().findFirst();
    }

    public Page<Txn> findByPortfolioFiltered(long portfolioId, TxnFilter filter, Pageable pageable) {
        var params = new MapSqlParameterSource()
                .addValue("portfolioId", portfolioId)
                .addValue("type", filter.type() == null ? null : filter.type().name())
                .addValue("symbol", filter.symbol())
                .addValue("from", filter.from() == null ? null : Timestamp.from(filter.from().atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("to", filter.to() == null ? null : Timestamp.from(filter.to().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()))
                .addValue("q", likePattern(filter.q()));

        // `q` spans symbol, instrument name and note because those are the three things visible
        // on a transaction row — a search box over a table should match what the reader can see.
        // LOWER() on both sides rather than trusting the column collation to be case-insensitive:
        // the leading wildcard already rules out an index, so it costs nothing to be explicit.
        String whereClause = """
                WHERE t.portfolio_id = :portfolioId
                  AND (:type IS NULL OR t.txn_type = :type)
                  AND (:symbol IS NULL OR i.symbol = :symbol)
                  AND (:from IS NULL OR t.executed_at >= :from)
                  AND (:to IS NULL OR t.executed_at < :to)
                  AND (:q IS NULL OR LOWER(i.symbol) LIKE :q ESCAPE '!'
                                  OR LOWER(i.name)   LIKE :q ESCAPE '!'
                                  OR LOWER(t.note)   LIKE :q ESCAPE '!')
                """;

        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM txn t LEFT JOIN instrument i ON t.instrument_id = i.id " + whereClause,
                params, Long.class);

        String pageSql = "SELECT t.* FROM txn t LEFT JOIN instrument i ON t.instrument_id = i.id " + whereClause
                + " ORDER BY t.executed_at DESC, t.id DESC LIMIT :limit OFFSET :offset";
        params.addValue("limit", pageable.size()).addValue("offset", pageable.offset());

        List<Txn> content = jdbcTemplate.query(pageSql, params, new TxnRowMapper());
        return new Page<>(content, pageable.page(), pageable.size(), total == null ? 0 : total);
    }

    /**
     * Wraps the free-text term in {@code %...%} with its own wildcards neutralised, so a search
     * for {@code "50%"} or {@code "A_B"} matches those characters literally instead of turning
     * into a match-anything pattern.
     *
     * <p>{@code !} is the escape character rather than the SQL default {@code \}: a backslash
     * would have to survive both Java text-block escaping and MySQL's own string-literal
     * backslash handling, and reads as four characters in the source to mean one in the query.
     * {@code !} means exactly itself at every layer — and is itself escaped here, so searching
     * for a literal {@code !} still works.
     */
    private static String likePattern(String q) {
        if (q == null) {
            return null;
        }
        String escaped = q.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }

    /** Returns the row count deleted (0 or 1) so the caller can tell "deleted" from "no such
     * transaction in this portfolio" without a separate existence check. */
    public int deleteByIdAndPortfolio(long txnId, long portfolioId) {
        String sql = "DELETE FROM txn WHERE id = :id AND portfolio_id = :portfolioId";
        var params = new MapSqlParameterSource().addValue("id", txnId).addValue("portfolioId", portfolioId);
        return jdbcTemplate.update(sql, params);
    }
}
