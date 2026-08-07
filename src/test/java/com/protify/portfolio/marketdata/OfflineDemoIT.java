package com.protify.portfolio.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.common.port.FxRateProvider;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import com.protify.portfolio.security.support.TestJwtSupport;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
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
 * D6-B1 / RISKS.md R11 — the automated proof this doc has promised since Day 2: with both
 * external providers replaced by ones that always throw (the closest a JUnit test gets to
 * "wifi off"), every read endpoint the customer's four verbs depend on still answers 200, never
 * a 5xx. The manual half of D6-B1 — actually disconnecting the network on a running demo
 * machine and clicking through {@code /docs/DEMO_SCRIPT.md} — cannot be done from here (no
 * browser, no live frontend, no network adapter to disable in this sandbox); this is the part
 * of it that a test can prove on every build, per RISKS.md R11's own description of it.
 *
 * <p>Real MySQL (Testcontainers), a real Google-shaped JWT, real routing — the only thing
 * replaced is the two provider beans, via {@code marketdata.provider}/{@code fx.provider} set
 * to a value neither {@code YahooMarketDataProvider} nor {@code FrankfurterFxRateProvider}
 * matches, so {@link ThrowingProvidersConfig}'s beans are the only candidates
 * {@code RateLimitedProviderConfig} and the FX services can wire in.
 */
@SpringBootTest(classes = PortfolioApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"marketdata.provider=offline-demo-stub", "fx.provider=offline-demo-stub"})
@AutoConfigureMockMvc
@Import({OfflineDemoIT.JwtDecoderTestConfig.class, OfflineDemoIT.ThrowingProvidersConfig.class})
class OfflineDemoIT extends AbstractIntegrationTest {

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

    /** Stands in for "the network is off" — every method throws, exactly like a captive portal
     * or a blocked outbound host would look to {@code RateLimitedProvider}/the FX services. */
    @TestConfiguration
    static class ThrowingProvidersConfig {

        @Bean
        MarketDataProvider offlineMarketDataProvider() {
            return new MarketDataProvider() {
                @Override
                public Optional<PriceQuote> latestPrice(String symbol) {
                    throw new RuntimeException("simulated: network is off");
                }

                @Override
                public List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to) {
                    throw new RuntimeException("simulated: network is off");
                }

                @Override
                public String sourceName() {
                    return "OFFLINE_TEST";
                }
            };
        }

        @Bean
        FxRateProvider offlineFxRateProvider() {
            return new FxRateProvider() {
                @Override
                public Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate on) {
                    throw new RuntimeException("simulated: network is off");
                }

                @Override
                public Map<CurrencyCode, BigDecimal> ratesFor(CurrencyCode base, LocalDate on) {
                    throw new RuntimeException("simulated: network is off");
                }

                @Override
                public String sourceName() {
                    return "OFFLINE_TEST";
                }
            };
        }
    }

    @Autowired
    private MockMvc mockMvc;

    private long portfolioId;
    private String token;

    @BeforeEach
    void setUp() {
        NamedParameterJdbcTemplate jdbc = jdbcTemplate();
        // Children before parents. The /performance case below memoises completed days into
        // portfolio_valuation_daily (D5-A2), so from the second test onwards this class has its
        // own snapshot rows pointing at the portfolio it is about to delete — fk_val_portfolio
        // then makes the DELETE FROM portfolio fail outright, exactly as
        // PortfolioRepository.deleteByIdAndUser warns.
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio_valuation_daily");
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        long relianceId = jdbc.queryForObject(
                "SELECT id FROM instrument WHERE symbol = 'RELIANCE'", Map.of(), Long.class);
        long aaplId = jdbc.queryForObject(
                "SELECT id FROM instrument WHERE symbol = 'AAPL'", Map.of(), Long.class);

        String sub = "offline-demo-" + System.nanoTime();
        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES (:sub, :email)",
                Map.of("sub", sub, "email", sub + "@example.com"));
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = :sub",
                Map.of("sub", sub), Long.class);

        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:userId, 'Offline Demo', 'INR')",
                Map.of("userId", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :userId",
                Map.of("userId", userId), Long.class);

        // Two currencies on purpose — a US-dollar holding in an INR-base portfolio exercises
        // the FX cross-rate path too, not just prices.
        insertTxnAndHolding(jdbc, relianceId, "10", "1450.25", "24.50", "INR");
        insertTxnAndHolding(jdbc, aaplId, "5", "190.00", "1.50", "USD");

        token = JWT_SUPPORT.validToken(sub, true);
    }

    private void insertTxnAndHolding(NamedParameterJdbcTemplate jdbc, long instrumentId, String qty,
            String price, String fees, String currency) {
        jdbc.update("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, :instrumentId, 'BUY', :qty, :price, :fees, :currency, '2026-06-15 10:15:00')
                """, Map.of("portfolioId", portfolioId, "instrumentId", instrumentId, "qty", qty,
                "price", price, "fees", fees, "currency", currency));
        jdbc.update("""
                INSERT INTO holding (portfolio_id, instrument_id, quantity, avg_cost, realised_pnl)
                VALUES (:portfolioId, :instrumentId, :qty, :price, 0)
                """, Map.of("portfolioId", portfolioId, "instrumentId", instrumentId, "qty", qty, "price", price));
    }

    private static Stream<Arguments> readEndpoints() {
        return Stream.of(
                Arguments.of("GET /portfolios", "/api/v1/portfolios"),
                Arguments.of("GET /portfolios/{id}", "/api/v1/portfolios/%d"),
                Arguments.of("GET /portfolios/{id}/holdings", "/api/v1/portfolios/%d/holdings"),
                Arguments.of("GET /portfolios/{id}/transactions", "/api/v1/portfolios/%d/transactions"),
                Arguments.of("GET /portfolios/{id}/valuation", "/api/v1/portfolios/%d/valuation"),
                Arguments.of("GET /portfolios/{id}/performance", "/api/v1/portfolios/%d/performance"),
                Arguments.of("GET /portfolios/{id}/allocation", "/api/v1/portfolios/%d/allocation"));
    }

    @ParameterizedTest(name = "{0} still returns 200 with both providers throwing")
    @MethodSource("readEndpoints")
    void everyReadEndpointStillReturns200WithBothProvidersThrowing(String description, String pathTemplate) throws Exception {
        String path = pathTemplate.contains("%d") ? pathTemplate.formatted(portfolioId) : pathTemplate;

        mockMvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @org.junit.jupiter.api.Test
    void healthStaysUpWithBothProvidersThrowing() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
