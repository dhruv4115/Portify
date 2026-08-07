package com.protify.portfolio.graphql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.NoOpDataSourceTestConfig;
import com.protify.portfolio.api.PortfolioApplication;
import com.protify.portfolio.api.allocation.AllocationService;
import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.dto.AllocationSliceDto;
import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.HoldingResponse;
import com.protify.portfolio.api.dto.InstrumentResponse;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.dto.PerformanceInterval;
import com.protify.portfolio.api.dto.PerformancePointDto;
import com.protify.portfolio.api.dto.PerformanceResponse;
import com.protify.portfolio.api.dto.PerformanceSummaryDto;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.api.portfolio.PortfolioSummary;
import com.protify.portfolio.api.portfolio.PortfolioSummaryProvider;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.api.valuation.PerformanceViewService;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.security.support.TestJwtSupport;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.client.MockMvcWebTestClient;

/**
 * day-5-dev-C.md D5-C1's four required behaviours. No Docker/MySQL needed — same recipe as
 * {@code UnauthorisedAccessIT}/{@code ReadEndpointSecurityTest}: DataSource/Flyway
 * autoconfiguration excluded, {@link NoOpDataSourceTestConfig} stands in, and every api/-layer
 * service the resolvers call is mocked, so nothing here ever reaches a repository.
 *
 * <p>{@link HttpGraphQlTester} is bound to {@link MockMvc} by hand (not {@code
 * @AutoConfigureHttpGraphQlTester}) specifically so the real {@code SecurityConfig} filter chain
 * and {@link GraphQlErrorEnrichmentInterceptor} both run for real on every request — an
 * unauthenticated request and an over-depth query both need the actual HTTP/security layer to
 * be provable at all.
 */
@SpringBootTest(
        classes = PortfolioApplication.class,
        properties = {
                "spring.autoconfigure.exclude="
                        + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                        + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration",
                "management.health.db.enabled=false"
        })
@Import({NoOpDataSourceTestConfig.class, GraphQlQueryTest.TestSupportConfig.class})
@AutoConfigureMockMvc
class GraphQlQueryTest {

    private static final TestJwtSupport JWT_SUPPORT = new TestJwtSupport();
    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);

    @Autowired
    private HttpGraphQlTester graphQlTester;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PortfolioService portfolioService;

    @MockitoBean
    private PortfolioSummaryProvider portfolioSummaryProvider;

    @MockitoBean
    private HoldingViewService holdingViewService;

    @MockitoBean
    private PerformanceViewService performanceViewService;

    @MockitoBean
    private AllocationService allocationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @TestConfiguration
    static class TestSupportConfig {

        /** Never invoked with a real token in this class other than through {@link
         * #authenticatedTester}, which mutates in its own bearer header per test. */
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

        @Bean
        HttpGraphQlTester httpGraphQlTester(MockMvc mockMvc) {
            WebTestClient client = MockMvcWebTestClient.bindTo(mockMvc).baseUrl("/graphql").build();
            return HttpGraphQlTester.create(client);
        }
    }

    @BeforeEach
    void setUp() {
        given(currentUserResolver.resolve()).willReturn(USER);
    }

    private HttpGraphQlTester authenticatedTester() {
        String token = JWT_SUPPORT.validToken("dashboard-user", true);
        return graphQlTester.mutate()
                .webTestClient(client -> client.defaultHeaders(headers -> headers.set("Authorization", "Bearer " + token)))
                .build();
    }

    private Portfolio portfolio(long id) {
        return new Portfolio(id, USER.id(), "Dashboard Portfolio", CurrencyCode.INR, Instant.EPOCH, Instant.EPOCH);
    }

    @Test
    void dashboardQueryReturnsAllFourSectionsInOneRoundTrip() {
        Portfolio found = portfolio(7L);
        given(portfolioService.getOrThrow(USER.id(), 7L)).willReturn(found);
        given(portfolioSummaryProvider.summarize(found)).willReturn(new PortfolioSummary(
                1, new BigDecimal("1000.0000"), new BigDecimal("900.0000"), new BigDecimal("50.0000"),
                new BigDecimal("100.0000"), BigDecimal.ZERO, "11.1100", DataQualityDto.empty()));

        InstrumentResponse instrument = new InstrumentResponse(1L, "RELIANCE", "Reliance Industries",
                AssetType.STOCK, CurrencyCode.INR, "NSE", "Energy");
        HoldingResponse holding = new HoldingResponse(instrument, "10.000000",
                MoneyDto.of(new BigDecimal("1450.2500"), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("1500.0000"), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("1000.0000"), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("900.0000"), CurrencyCode.INR),
                MoneyDto.of(new BigDecimal("100.0000"), CurrencyCode.INR),
                "11.1100", MoneyDto.zero(CurrencyCode.INR), "100.0000", "1.00000000", DataQualityDto.empty());
        given(holdingViewService.list(USER.id(), 7L, CurrencyCode.INR, false)).willReturn(List.of(holding));

        PerformanceResponse performance = new PerformanceResponse(7L, CurrencyCode.INR,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1), PerformanceInterval.DAILY,
                List.of(new PerformancePointDto(LocalDate.of(2026, 6, 1),
                        MoneyDto.of(new BigDecimal("1000.0000"), CurrencyCode.INR),
                        MoneyDto.of(new BigDecimal("900.0000"), CurrencyCode.INR),
                        MoneyDto.of(new BigDecimal("50.0000"), CurrencyCode.INR),
                        MoneyDto.of(new BigDecimal("1050.0000"), CurrencyCode.INR),
                        MoneyDto.of(new BigDecimal("100.0000"), CurrencyCode.INR), false)),
                new PerformanceSummaryDto(MoneyDto.zero(CurrencyCode.INR), MoneyDto.of(new BigDecimal("1050.0000"), CurrencyCode.INR),
                        MoneyDto.of(new BigDecimal("1050.0000"), CurrencyCode.INR), "12.5000", MoneyDto.zero(CurrencyCode.INR)),
                DataQualityDto.empty());
        given(performanceViewService.get(USER.id(), 7L, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1),
                PerformanceInterval.DAILY, CurrencyCode.INR)).willReturn(performance);

        AllocationResponse allocation = new AllocationResponse(7L, AllocationDimension.SECTOR, CurrencyCode.INR,
                MoneyDto.of(new BigDecimal("1000.0000"), CurrencyCode.INR),
                List.of(new AllocationSliceDto("Energy", "Energy", MoneyDto.of(new BigDecimal("1000.0000"), CurrencyCode.INR),
                        "100.0000", 1)),
                DataQualityDto.empty());
        given(allocationService.compute(USER.id(), 7L, AllocationDimension.SECTOR, CurrencyCode.INR)).willReturn(allocation);

        String document = """
                query Dashboard($id: ID!) {
                  portfolio(id: $id) {
                    name
                    totalValue { amount currency }
                    holdings(currency: INR) {
                      quantity
                      instrument { symbol }
                    }
                    performance(from: "2026-01-01", to: "2026-06-01", currency: INR) {
                      summary { percentChange }
                      points { date }
                    }
                    allocation(by: SECTOR, currency: INR) {
                      total { amount }
                      slices { label weightPct }
                    }
                  }
                }
                """;

        authenticatedTester().document(document)
                .variable("id", 7)
                .execute()
                .path("portfolio.name").entity(String.class).isEqualTo("Dashboard Portfolio")
                .path("portfolio.totalValue.amount").entity(String.class).isEqualTo("1050.0000")
                .path("portfolio.holdings").entityList(Object.class).hasSize(1)
                .path("portfolio.holdings[0].instrument.symbol").entity(String.class).isEqualTo("RELIANCE")
                .path("portfolio.performance.summary.percentChange").entity(String.class).isEqualTo("12.5000")
                .path("portfolio.performance.points").entityList(Object.class).hasSize(1)
                .path("portfolio.allocation.total.amount").entity(String.class).isEqualTo("1000.0000")
                .path("portfolio.allocation.slices").entityList(Object.class).hasSize(1);
    }

    @Test
    void anotherUsersPortfolioResolvesToNull() {
        given(portfolioService.getOrThrow(USER.id(), 999L)).willThrow(new NotFoundException("portfolio", 999L));

        authenticatedTester().document("query($id: ID!) { portfolio(id: $id) { name } }")
                .variable("id", 999)
                .execute()
                .errors().verify()
                .path("portfolio").valueIsNull();
    }

    @Test
    void unauthenticatedQueryIsRejected() throws Exception {
        mockMvc.perform(post("/graphql")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"{ __typename }\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void queryOverDepthSixIsRejected() {
        String tooDeep = """
                query TooDeep {
                  __schema {
                    types {
                      fields {
                        type {
                          fields {
                            type {
                              fields {
                                name
                              }
                            }
                          }
                        }
                      }
                    }
                  }
                }
                """;

        authenticatedTester().document(tooDeep)
                .execute()
                .errors()
                .expect(error -> {
                    assertThat(error.getExtensions()).isNotNull();
                    assertThat(error.getExtensions()).containsKey("correlationId");
                    assertThat(error.getExtensions()).containsKey("classification");
                    return true;
                })
                .verify();
    }
}
