package com.protify.portfolio.support;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

/**
 * The {@code KeyHolder} + {@code RETURN_GENERATED_KEYS} insert helper, written once
 * (REFERENCE_DESIGN §1's MySQL gotchas). No other class in the codebase constructs a
 * {@link KeyHolder} — every {@code *Repository} extends this and calls {@link #insert} instead.
 */
public abstract class BaseRepository {

    protected final NamedParameterJdbcTemplate jdbcTemplate;

    protected BaseRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Runs an insert against a table with an auto-increment {@code id} primary key and returns
     * the generated value.
     */
    protected long insert(String sql, SqlParameterSource params) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(sql, params, keyHolder, new String[] {"id"});
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Insert did not return a generated key: " + sql);
        }
        return key.longValue();
    }

    /**
     * Runs an {@code INSERT ... ON DUPLICATE KEY UPDATE} upsert. The caller supplies the full
     * statement, including the update clause — this only exists so every repository upserts
     * through the same named, reviewable path. Returns the JDBC update count.
     */
    protected int upsert(String sql, SqlParameterSource params) {
        return jdbcTemplate.update(sql, params);
    }
}
