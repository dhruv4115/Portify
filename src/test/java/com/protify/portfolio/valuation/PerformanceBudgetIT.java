package com.protify.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.UpstreamException;
import com.protify.portfolio.common.port.FxRateProvider;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.security.support.TestJwtSupport;
import com.protify.portfolio.support.AbstractIntegrationTest;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-5-dev-A.md D5-A3 / TEST_PLAN.md §5 — <b>"no endpoint exceeds 500 ms with 500
 * transactions"</b>, and it is measured through the endpoints rather than through the services,
 * because that is the claim. A service-level timing would leave out serialisation, security and
 * the mapper layer, all of which the customer waits for.
 *
 * <p>The fixture is the one the brief specifies: 500 transactions across 20 instruments in three
 * currencies, over 365 days of the real V11/V12 seed.
 *
 * <p><b>On timing assertions in CI.</b> A wall-clock budget is the only honest way to state this
 * requirement, but it makes the test sensitive to a loaded machine. Two things keep it from being
 * flaky theatre: every path is warmed before it is measured, so class loading and JIT are not
 * what gets timed, and the budget is 500 ms against paths that come in an order of magnitude
 * under it. If this ever fails, the first question is whether an index or a batch was lost — the
 * numbers do not sit anywhere near the line.
 */
@SpringBootTest(classes = PortfolioApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@AutoConfigureMockMvc
@Import(PerformanceBudgetIT.JwtDecoderTestConfig.class)
class PerformanceBudgetIT extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        // PER_CLASS below builds the test instance — and therefore the Spring context — before
        // @Testcontainers' own beforeAll callback has started the container, so the mapped port
        // is not available yet. Starting it here is the same guard AbstractIntegrationTest.
        // jdbcTemplate() uses, and start() is a no-op on an already-running container.
        if (!MYSQL.isRunning()) {
            MYSQL.start();
        }
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

    private static final int TRANSACTION_COUNT = 500;
    private static final int INSTRUMENT_COUNT = 20;
    private static final int WINDOW_DAYS = 365;
    private static final long BUDGET_MILLIS = 500L;

    /** WebConfig applies this prefix to every controller — see its javadoc. */
    private static final String API = "/api/v1";

    private static final LocalDate FROM = LocalDate.parse("2025-01-02");
    private static final LocalDate TO = FROM.plusDays(WINDOW_DAYS - 1L);

    /**
     * <b>Both upstream providers are replaced with ones that always fail</b> — the shape
     * TEST_PLAN.md §5 specifies for {@code OfflineDemoIT}, and {@code @MockitoBean} replaces the
     * existing primary bean rather than competing with it.
     *
     * <p>Two reasons, and the second is the important one. Practically, an unstubbed run made 119
     * calls to Yahoo and 128 to Frankfurter and took seven minutes; a build must not depend on
     * someone else's uptime. Substantively, <b>a budget measured with live providers measures the
     * wrong thing</b>: it reports network latency rather than this codebase, and it would go
     * green or red on a stranger's servers. Failing them forces the documented fallback chain —
     * Caffeine, then {@code price_history}/{@code fx_rate}, then the seed — which is exactly the
     * path the demo runs on with the wifi off (CLAUDE.md non-negotiable #10), and the one worth
     * holding to a budget.
     */
    @MockitoBean
    private MarketDataProvider marketDataProvider;
    @MockitoBean
    private FxRateProvider fxRateProvider;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private String token;
    private long portfolioId;

    @BeforeAll
    void seed() {
        UpstreamException offline = new UpstreamException("test", "provider offline in PerformanceBudgetIT");
        given(marketDataProvider.latestPrice(anyString())).willThrow(offline);
        given(marketDataProvider.dailyCloses(anyString(), any(), any())).willThrow(offline);
        given(fxRateProvider.rate(any(), any(), any())).willThrow(offline);
        given(fxRateProvider.ratesFor(any(), any())).willThrow(offline);

        jdbc.getJdbcTemplate().update("DELETE FROM portfolio_valuation_daily");
        jdbc.getJdbcTemplate().update("DELETE FROM holding");
        jdbc.getJdbcTemplate().update("DELETE FROM txn");
        jdbc.getJdbcTemplate().update("DELETE FROM portfolio");
        jdbc.getJdbcTemplate().update("DELETE FROM app_user");

        String subject = "budget-subject";
        token = JWT_SUPPORT.validToken(subject, true);
        jdbc.update("INSERT INTO app_user (google_sub, email) VALUES (:sub, 'budget@example.com')",
                Map.of("sub", subject));
        long userId = jdbc.queryForObject("SELECT id FROM app_user WHERE google_sub = :sub",
                Map.of("sub", subject), Long.class);
        jdbc.update("INSERT INTO portfolio (user_id, name, base_currency) VALUES (:u, 'Budget', 'USD')",
                Map.of("u", userId));
        portfolioId = jdbc.queryForObject("SELECT id FROM portfolio WHERE user_id = :u",
                Map.of("u", userId), Long.class);

        // 20 instruments across three currencies, so every point pays for an FX cross rate.
        Map<Long, CurrencyCode> instruments = new LinkedHashMap<>();
        for (Map<String, Object> row : jdbc.queryForList("""
                SELECT id, currency FROM instrument
                WHERE currency IN ('USD','INR','GBP') ORDER BY id LIMIT :n
                """, Map.of("n", INSTRUMENT_COUNT))) {
            instruments.put(((Number) row.get("id")).longValue(),
                    CurrencyCode.fromDbValue((String) row.get("currency")).orElseThrow());
        }
        assertThat(instruments).as("three-currency instrument fixture").hasSizeGreaterThanOrEqualTo(3);

        List<Long> ids = new ArrayList<>(instruments.keySet());
        List<MapSqlParameterSource> batch = new ArrayList<>();
        // Opening deposit, then 499 buys spread evenly across the window.
        batch.add(txnParams(null, "DEPOSIT", "0", "5000000.00", CurrencyCode.USD, FROM));
        for (int i = 1; i < TRANSACTION_COUNT; i++) {
            long instrumentId = ids.get(i % ids.size());
            batch.add(txnParams(instrumentId, "BUY", "2", "150.00", instruments.get(instrumentId),
                    FROM.plusDays((long) i % WINDOW_DAYS)));
        }
        jdbc.batchUpdate("""
                INSERT INTO txn (portfolio_id, instrument_id, txn_type, quantity, price, fees, currency, executed_at)
                VALUES (:portfolioId, :instrumentId, :txnType, :quantity, :price, 0, :currency, :executedAt)
                """, batch.toArray(new MapSqlParameterSource[0]));
    }

    private MapSqlParameterSource txnParams(Long instrumentId, String type, String qty, String price,
            CurrencyCode currency, LocalDate date) {
        return new MapSqlParameterSource()
                .addValue("portfolioId", portfolioId)
                .addValue("instrumentId", instrumentId)
                .addValue("txnType", type)
                .addValue("quantity", qty)
                .addValue("price", price)
                .addValue("currency", currency.name())
                .addValue("executedAt", Timestamp.from(date.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }

    /** Warms the path, then times it. Returns the measured milliseconds. */
    private long timeGet(String path) throws Exception {
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());

        long startNanos = System.nanoTime();
        mockMvc.perform(get(path).header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private void assertWithinBudget(String path) throws Exception {
        long elapsed = timeGet(path);
        System.out.printf("  budget: %-70s %4d ms%n", path, elapsed);
        assertThat(elapsed).as("%s with %d transactions over %d days", path, TRANSACTION_COUNT, WINDOW_DAYS)
                .isLessThan(BUDGET_MILLIS);
    }

    /** The one most likely to get slow, and the reason the whole task exists: a 365-day series
     * over 20 instruments in three currencies. */
    @Test
    void performanceSeriesStaysWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/performance?from=%s&to=%s".formatted(portfolioId, FROM, TO));
    }

    /** The same window in a non-base currency, so every point resolves an FX cross rate. */
    @Test
    void performanceSeriesInANonBaseCurrencyStaysWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/performance?from=%s&to=%s&currency=INR".formatted(portfolioId, FROM, TO));
    }

    @Test
    void valuationStaysWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/valuation?asOf=%s".formatted(portfolioId, TO));
    }

    @Test
    void allocationStaysWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/allocation?by=SECTOR&asOf=%s".formatted(portfolioId, TO));
    }

    @Test
    void holdingsStayWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/holdings".formatted(portfolioId));
    }

    @Test
    void transactionsPageStaysWithinBudget() throws Exception {
        assertWithinBudget(API + "/portfolios/%d/transactions?page=0&size=50".formatted(portfolioId));
    }
}
