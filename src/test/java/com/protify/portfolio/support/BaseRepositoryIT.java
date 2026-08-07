package com.protify.portfolio.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Exercises {@link BaseRepository} against {@code app_user} (real V1 schema, auto-increment
 * {@code id} plus a natural unique key on {@code google_sub}) rather than a throwaway table —
 * this is the same shape Dev B's JIT user provisioning needs tomorrow.
 */
class BaseRepositoryIT extends AbstractIntegrationTest {

    private TestUserRepository repository;

    /**
     * Clears the dependents before {@code app_user}, the same order the other integration tests
     * that reset this shared schema use. {@code DELETE FROM app_user} on its own was enough
     * while nothing ever owned a portfolio; once {@code V5__demo_seed.sql} began seeding two
     * users who do, it fails on {@code fk_portfolio_user} instead.
     */
    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate jdbcTemplate = jdbcTemplate();
        // app_user is the root of the FK chain, so clearing it means clearing everything that
        // points at it first. Failsafe's default runOrder is `filesystem`, which differs between
        // a developer's machine and the Linux CI runner, so "whichever class ran before this one
        // happened to leave no portfolios behind" is not a property this can rely on.
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM portfolio_valuation_daily");
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM holding");
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM txn");
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM app_user");
        repository = new TestUserRepository(jdbcTemplate);
    }

    @Test
    void shouldReturnTheGeneratedIdOnInsert() {
        long id = repository.insertUser("sub-1", "user1@example.com");

        assertThat(id).isPositive();
    }

    @Test
    void shouldAssignDifferentIdsToSuccessiveInserts() {
        long first = repository.insertUser("sub-1", "user1@example.com");
        long second = repository.insertUser("sub-2", "user2@example.com");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void shouldBeIdempotentUnderRepeatedUpsert() {
        repository.upsertUser("sub-3", "user3@example.com", "First Name");
        repository.upsertUser("sub-3", "user3@example.com", "Second Name");
        repository.upsertUser("sub-3", "user3@example.com", "Second Name");

        assertThat(repository.countByGoogleSub("sub-3")).isEqualTo(1);
        assertThat(repository.displayNameOf("sub-3")).isEqualTo("Second Name");
    }

    private static final class TestUserRepository extends BaseRepository {

        TestUserRepository(NamedParameterJdbcTemplate jdbcTemplate) {
            super(jdbcTemplate);
        }

        long insertUser(String googleSub, String email) {
            String sql = "INSERT INTO app_user (google_sub, email) VALUES (:googleSub, :email)";
            var params = new MapSqlParameterSource(Map.of("googleSub", googleSub, "email", email));
            return insert(sql, params);
        }

        int upsertUser(String googleSub, String email, String displayName) {
            String sql = """
                    INSERT INTO app_user (google_sub, email, display_name)
                    VALUES (:googleSub, :email, :displayName)
                    ON DUPLICATE KEY UPDATE display_name = VALUES(display_name)
                    """;
            var params = new MapSqlParameterSource()
                    .addValue("googleSub", googleSub)
                    .addValue("email", email)
                    .addValue("displayName", displayName);
            return upsert(sql, params);
        }

        int countByGoogleSub(String googleSub) {
            String sql = "SELECT COUNT(*) FROM app_user WHERE google_sub = :googleSub";
            Integer count = jdbcTemplate.queryForObject(sql, Map.of("googleSub", googleSub), Integer.class);
            return count == null ? 0 : count;
        }

        String displayNameOf(String googleSub) {
            String sql = "SELECT display_name FROM app_user WHERE google_sub = :googleSub";
            return jdbcTemplate.queryForObject(sql, Map.of("googleSub", googleSub), String.class);
        }
    }
}
