package com.protify.portfolio.api.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.security.support.TestJwtSupport;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * day-4-dev-C.md D4-C4 / API_CONTRACT.md §5 — this test <em>is</em> ADR-0011's central claim:
 * changing a portfolio's base currency rewrites no stored row. Real MySQL (Testcontainers) and
 * the real security filter chain, because the point being proved is specifically about what
 * {@code PATCH /portfolios/{id}} does to the database — a mocked service could not tell us this.
 */
@SpringBootTest(classes = PortfolioApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@Import(BaseCurrencyImmutabilityIT.JwtDecoderTestConfig.class)
class BaseCurrencyImmutabilityIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final TestJwtSupport JWT_SUPPORT = new TestJwtSupport();
    private static final ObjectMapper JSON = new ObjectMapper();

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
    private long portfolioId;
    private String token;

    @BeforeEach
    void setUp() {
        jdbc = jdbcTemplate();
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        String sub = "base-currency-it-" + System.nanoTime();
        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES (:sub, :email)",
                Map.of("sub", sub, "email", sub + "@example.com"));
        long userId = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE google_sub = :sub", Map.of("sub", sub), Long.class);

        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Growth', 'INR')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject(
                "SELECT id FROM portfolio WHERE user_id = :userId", Map.of("userId", userId), Long.class);

        long relianceId = jdbc.queryForObject(
                "SELECT id FROM instrument WHERE symbol = 'RELIANCE'", Map.of(), Long.class);

        // Dates well inside V12's seeded fx_rate range (2024-08-02 .. 2026-07-31) so the
        // trade-date FX lookup always resolves to a real rate, never the BigDecimal.ONE
        // fallback used when no quote exists for that date.
        jdbc.update("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, NULL, 'DEPOSIT', 0, 100000, 0, 'INR', '2026-06-01 09:00:00')
                """, Map.of("portfolioId", portfolioId));
        jdbc.update("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, :instrumentId, 'BUY', 10, 1450.25, 24.50, 'INR', '2026-06-15 10:15:00')
                """, Map.of("portfolioId", portfolioId, "instrumentId", relianceId));
        jdbc.update("""
                INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
                VALUES (:portfolioId, :instrumentId, 10, 1452.70, 0)
                """, Map.of("portfolioId", portfolioId, "instrumentId", relianceId));

        token = JWT_SUPPORT.validToken(sub, true);
    }

    @Test
    void patchingBaseCurrencyRewritesNoTxnOrHoldingRow() throws Exception {
        List<Map<String, Object>> txnBefore = jdbc.getJdbcTemplate().queryForList("SELECT * FROM txn ORDER BY id");
        List<Map<String, Object>> holdingBefore =
                jdbc.getJdbcTemplate().queryForList("SELECT * FROM holding ORDER BY id");

        mockMvc.perform(patch("/api/v1/portfolios/" + portfolioId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isOk());

        List<Map<String, Object>> txnAfter = jdbc.getJdbcTemplate().queryForList("SELECT * FROM txn ORDER BY id");
        List<Map<String, Object>> holdingAfter =
                jdbc.getJdbcTemplate().queryForList("SELECT * FROM holding ORDER BY id");

        assertThat(txnAfter).isEqualTo(txnBefore);
        assertThat(holdingAfter).isEqualTo(holdingBefore);
    }

    @Test
    void patchingBaseCurrencyReExpressesEveryMoneyFieldButKeepsAvgCostNative() throws Exception {
        JsonNode valuationBefore = getJson("/api/v1/portfolios/" + portfolioId + "/valuation");
        assertThat(valuationBefore.at("/cashBalance/currency").asText()).isEqualTo("INR");
        BigDecimal cashBefore = new BigDecimal(valuationBefore.at("/cashBalance/amount").asText());

        mockMvc.perform(patch("/api/v1/portfolios/" + portfolioId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isOk());

        JsonNode valuationAfter = getJson("/api/v1/portfolios/" + portfolioId + "/valuation");
        assertThat(valuationAfter.at("/cashBalance/currency").asText()).isEqualTo("USD");
        BigDecimal cashAfter = new BigDecimal(valuationAfter.at("/cashBalance/amount").asText());

        // INR -> USD is on the order of 1/85-1/100 today; a real conversion, not a relabel.
        assertThat(cashAfter).isGreaterThan(BigDecimal.ZERO);
        assertThat(cashAfter).isLessThan(cashBefore.divide(BigDecimal.TEN));

        JsonNode holdingsAfter = getJson("/api/v1/portfolios/" + portfolioId + "/holdings");
        // avgCost stays in RELIANCE's own native currency (INR) regardless of the portfolio's
        // base currency (API_CONTRACT.md §0.3) — ADR-0011's whole point.
        assertThat(holdingsAfter.get(0).at("/avgCost/currency").asText()).isEqualTo("INR");
    }

    private JsonNode getJson(String path) throws Exception {
        String body = mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body);
    }
}
