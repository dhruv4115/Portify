package com.protify.portfolio.api.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.security.support.TestJwtSupport;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-4-dev-C.md D4-C1 / TEST_PLAN.md §4.7 — <b>the test an assessor at an investment bank
 * looks for first.</b> User A, authenticated for real (a genuine Google-shaped JWT, validated
 * by the real {@code SecurityConfig}), attempts every user-scoped operation against user B's
 * portfolio. Every one of them must answer 404, never 403 — a 403 would confirm the row exists,
 * which is itself a leak.
 *
 * <p>Real MySQL (Testcontainers): the point being proved is what {@code PortfolioRepository}'s
 * {@code WHERE user_id = :userId} clause actually does against real rows, which a mocked
 * service cannot demonstrate.
 *
 * <p>Six read endpoints share one shape (GET, 404, nothing about B changes) and are
 * parameterised together; the four mutating ones (PATCH, DELETE portfolio, POST transaction,
 * DELETE transaction) each need their own body/preconditions and get their own test method.
 * {@code GET /graphql {portfolio(id: B)}} from TEST_PLAN.md §4.7's table is not included: there
 * is no GraphQL endpoint in this codebase yet (Day 5, per the hand-off).
 *
 * <p><b>The mutation-test half of D4-C1 — temporarily deleting the {@code AND user_id =
 * :userId} clause from {@code PortfolioRepository.findByIdAndUser}, confirming this suite goes
 * red, then reverting it — needs to be run by hand against a real Docker daemon.</b> This
 * sandbox has none (documented repeatedly across every earlier day's session), so that step
 * could not be executed or screenshotted here. See the hand-off notes in this session's final
 * summary for exactly what to run.
 */
@SpringBootTest(classes = PortfolioApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(CrossUserAccessIT.JwtDecoderTestConfig.class)
class CrossUserAccessIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final TestJwtSupport JWT_SUPPORT = new TestJwtSupport();

    @TestConfiguration
    static class JwtDecoderTestConfig {
        @Bean
        @Primary
        JwtDecoder jwtDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(JWT_SUPPORT.publicKey).build();
            OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(TestJwtSupport.ISSUER);
            OAuth2TokenValidator<Jwt> withAudience = new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                    aud -> aud != null && aud.contains(TestJwtSupport.CLIENT_ID));
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience));
            return decoder;
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private NamedParameterJdbcTemplate jdbc;
    private long portfolioAId;
    private long portfolioBId;
    private long txnBId;
    private String tokenA;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        long relianceId = jdbc.queryForObject(
                "SELECT id FROM instrument WHERE symbol = 'RELIANCE'", Map.of(), Long.class);

        String subA = "cross-user-a-" + System.nanoTime();
        String subB = "cross-user-b-" + System.nanoTime();
        long userAId = insertUser(subA);
        long userBId = insertUser(subB);

        portfolioAId = insertPortfolio(userAId, "A Portfolio");
        portfolioBId = insertPortfolio(userBId, "B Portfolio");
        insertTxnAndHolding(portfolioBId, relianceId);
        txnBId = jdbc.queryForObject(
                "SELECT id FROM txn WHERE portfolio_id = :pid", Map.of("pid", portfolioBId), Long.class);

        tokenA = JWT_SUPPORT.validToken(subA, true);
    }

    private long insertUser(String sub) {
        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES (:sub, :email)",
                Map.of("sub", sub, "email", sub + "@example.com"));
        return jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = :sub", Map.of("sub", sub), Long.class);
    }

    private long insertPortfolio(long userId, String name) {
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, :name, 'INR')",
                Map.of("userId", userId, "name", name));
        return jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId AND name = :name",
                Map.of("userId", userId, "name", name), Long.class);
    }

    private void insertTxnAndHolding(long portfolioId, long instrumentId) {
        jdbc.update("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, :instrumentId, 'BUY', 10, 1450.25, 24.50, 'INR', '2026-06-15 10:15:00')
                """, Map.of("portfolioId", portfolioId, "instrumentId", instrumentId));
        jdbc.update("""
                INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
                VALUES (:portfolioId, :instrumentId, 10, 1452.70, 0)
                """, Map.of("portfolioId", portfolioId, "instrumentId", instrumentId));
    }

    private static Stream<Arguments> readOnlyUserScopedEndpoints() {
        return Stream.of(
                Arguments.of("GET /portfolios/{id}", "/api/v1/portfolios/%d"),
                Arguments.of("GET /portfolios/{id}/holdings", "/api/v1/portfolios/%d/holdings"),
                Arguments.of("GET /portfolios/{id}/transactions", "/api/v1/portfolios/%d/transactions"),
                Arguments.of("GET /portfolios/{id}/valuation", "/api/v1/portfolios/%d/valuation"),
                Arguments.of("GET /portfolios/{id}/performance", "/api/v1/portfolios/%d/performance"),
                Arguments.of("GET /portfolios/{id}/allocation", "/api/v1/portfolios/%d/allocation"));
    }

    @ParameterizedTest(name = "{0} on another user''s portfolio returns 404, never 403")
    @MethodSource("readOnlyUserScopedEndpoints")
    void everyReadEndpointReturns404ForAnotherUsersPortfolio(String description, String pathTemplate) throws Exception {
        String path = pathTemplate.formatted(portfolioBId);

        mockMvc.perform(get(path).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertBPortfolioAndDataUnchanged();
    }

    @Test
    void patchOnAnotherUsersPortfolioReturns404() throws Exception {
        mockMvc.perform(patch("/api/v1/portfolios/" + portfolioBId)
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hijacked\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));

        assertBPortfolioAndDataUnchanged();
    }

    @Test
    void deleteOnAnotherUsersPortfolioReturns404AndDoesNotDeleteIt() throws Exception {
        mockMvc.perform(delete("/api/v1/portfolios/" + portfolioBId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        assertBPortfolioAndDataUnchanged();
    }

    @Test
    void postTransactionOnAnotherUsersPortfolioReturns404AndWritesNothing() throws Exception {
        String body = """
                {"type":"BUY","symbol":"RELIANCE","quantity":"1","price":"100.00",
                 "currency":"INR","executedAt":"2026-06-20T09:00:00Z"}
                """;

        mockMvc.perform(post("/api/v1/portfolios/" + portfolioBId + "/transactions")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        assertBPortfolioAndDataUnchanged();
    }

    @Test
    void deleteAnotherUsersTransactionThroughTheirOwnPortfolioPathReturns404() throws Exception {
        mockMvc.perform(delete("/api/v1/portfolios/" + portfolioBId + "/transactions/" + txnBId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        assertBPortfolioAndDataUnchanged();
    }

    /**
     * The realistic version of this bug, and the case a lazier test would miss (day-4-dev-C.md):
     * user A's own portfolio path is legitimately A's — only the transaction id belongs to B.
     * This catches a repository method scoped by portfolio but not by transaction ownership.
     */
    @Test
    void shouldNotDeleteOtherUsersTransactionThroughOwnPortfolioPath() throws Exception {
        mockMvc.perform(delete("/api/v1/portfolios/" + portfolioAId + "/transactions/" + txnBId)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());

        assertBPortfolioAndDataUnchanged();
    }

    private void assertBPortfolioAndDataUnchanged() {
        Map<String, Object> portfolio = jdbc.getJdbcTemplate()
                .queryForMap("SELECT * FROM portfolio WHERE id = " + portfolioBId);
        assertThat(portfolio.get("name")).isEqualTo("B Portfolio");

        Long txnCount = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM txn WHERE portfolio_id = " + portfolioBId, Long.class);
        assertThat(txnCount).isEqualTo(1L);

        Long holdingCount = jdbc.getJdbcTemplate().queryForObject(
                "SELECT COUNT(*) FROM holding WHERE portfolio_id = " + portfolioBId, Long.class);
        assertThat(holdingCount).isEqualTo(1L);
    }
}
