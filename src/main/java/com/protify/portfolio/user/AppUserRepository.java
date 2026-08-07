package com.protify.portfolio.user;

import com.protify.portfolio.support.BaseRepository;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * D1-B2 — {@code NamedParameterJdbcTemplate}, explicit SQL, hand-written {@link AppUserRowMapper}.
 * Generated-key insert goes through {@link BaseRepository}, per CLAUDE.md's "write it once,
 * reuse it" rule — no other class in the codebase constructs a {@code KeyHolder}.
 */
@Repository
public class AppUserRepository extends BaseRepository {

    private static final AppUserRowMapper ROW_MAPPER = new AppUserRowMapper();

    private static final String SELECT_COLUMNS =
            "id, google_sub, email, display_name, picture_url, created_at, updated_at";

    private final NamedParameterJdbcTemplate jdbc;

    public AppUserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
        this.jdbc = jdbcTemplate;
    }

    public Optional<AppUser> findByGoogleSub(String googleSub) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM app_user WHERE google_sub = :googleSub";
        return jdbc.query(sql, new MapSqlParameterSource("googleSub", googleSub), ROW_MAPPER)
                .stream()
                .findFirst();
    }

    public Optional<AppUser> findById(Long id) {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM app_user WHERE id = :id";
        return jdbc.query(sql, new MapSqlParameterSource("id", id), ROW_MAPPER)
                .stream()
                .findFirst();
    }

    /**
     * Inserts a new row. Throws {@code DuplicateKeyException} if {@code googleSub} or
     * {@code email} already exists (unique constraints {@code uk_app_user_google_sub},
     * {@code uk_app_user_email}) — callers handle the concurrent-first-sign-in race by catching
     * this and re-reading, never by pre-checking existence (that check-then-act would itself
     * race).
     */
    public AppUser insert(String googleSub, String email, String displayName, String pictureUrl) {
        String sql = """
                INSERT INTO app_user (google_sub, email, display_name, picture_url)
                VALUES (:googleSub, :email, :displayName, :pictureUrl)
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("googleSub", googleSub)
                .addValue("email", email)
                .addValue("displayName", displayName)
                .addValue("pictureUrl", pictureUrl);

        long generatedId = insert(sql, params);

        return findById(generatedId)
                .orElseThrow(() -> new IllegalStateException(
                        "app_user row " + generatedId + " missing immediately after insert"));
    }

    /** V13's columns are not in {@link #SELECT_COLUMNS} — {@link AppUser} deliberately does not
     * carry them, so every read of the account for auth purposes stays the same three-column
     * shape it has always been. */
    public UserPreferences findPreferences(long userId) {
        String sql = "SELECT theme, language_code, density, motion FROM app_user WHERE id = :id";
        return jdbc.query(sql, new MapSqlParameterSource("id", userId), PREFERENCES_ROW_MAPPER)
                .stream()
                .findFirst()
                .orElseGet(UserPreferences::none);
    }

    /**
     * Partial update: a {@code null} field leaves the stored value alone rather than clearing
     * it, which is what PATCH means. {@code COALESCE} does that in one statement, so there is
     * no read-modify-write and therefore no lost update when two devices save at once.
     *
     * @return the row as it stands afterwards, so the caller echoes back what was actually
     *         stored rather than what it hoped would be
     */
    public UserPreferences updatePreferences(long userId, UserPreferences changes) {
        String sql = """
                UPDATE app_user SET
                    theme         = COALESCE(:theme, theme),
                    language_code = COALESCE(:language, language_code),
                    density       = COALESCE(:density, density),
                    motion        = COALESCE(:motion, motion)
                WHERE id = :id
                """;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", userId)
                .addValue("theme", changes.theme())
                .addValue("language", changes.language())
                .addValue("density", changes.density())
                .addValue("motion", changes.motion());

        jdbc.update(sql, params);
        return findPreferences(userId);
    }

    private static final RowMapper<UserPreferences> PREFERENCES_ROW_MAPPER =
            (rs, rowNum) -> new UserPreferences(
                    rs.getString("theme"),
                    rs.getString("language_code"),
                    rs.getString("density"),
                    rs.getString("motion"));
}
