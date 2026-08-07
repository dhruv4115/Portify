package com.protify.portfolio.user;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Hand-written {@link RowMapper} for {@code app_user} — no JPA, no Hibernate (CLAUDE.md
 * non-negotiable #2).
 */
public class AppUserRowMapper implements RowMapper<AppUser> {

    @Override
    public AppUser mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new AppUser(
                rs.getLong("id"),
                rs.getString("google_sub"),
                rs.getString("email"),
                rs.getString("display_name"),
                rs.getString("picture_url"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
