package com.protify.portfolio.api.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.ValuationMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.error.ValidationException;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-3-dev-C.md D3-C2, API_CONTRACT.md §11. Dev A's {@link ValuationService} is stubbed — the
 * maths is theirs and is covered by {@code ValuationServiceTest}; what is tested here is the
 * contract surface: defaults, the money-as-string shape, nullable fields surviving as {@code
 * null}, and each documented status code.
 */
@WebMvcTest(controllers = ValuationController.class)
@Import(ValuationMapper.class)
class ValuationControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);
    private static final long PORTFOLIO_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ValuationService valuationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @BeforeEach
    void stubCurrentUser() {
        given(currentUserResolver.resolve()).willReturn(USER);
    }

    private static ValuationResult populated(LocalDate asOf) {
        return new ValuationResult(PORTFOLIO_ID, asOf, CurrencyCode.INR,
                new BigDecimal("389442.10"), new BigDecimal("374662.63"), new BigDecimal("25000.00"),
                new BigDecimal("414442.10"), new BigDecimal("14779.47"), new BigDecimal("4120.00"),
                new BigDecimal("3.9400"), 3,
                LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30), false);
    }

    @Test
    void shouldReturnValuationWithEveryAmountAsAStringInTheBaseCurrency() throws Exception {
        LocalDate asOf = LocalDate.of(2026, 6, 30);
        given(valuationService.valuate(USER.id(), PORTFOLIO_ID, asOf, null)).willReturn(populated(asOf));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("asOf", "2026-06-30"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value(7))
                .andExpect(jsonPath("$.asOf").value("2026-06-30"))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.marketValue.amount").value("389442.1000"))
                .andExpect(jsonPath("$.marketValue.currency").value("INR"))
                .andExpect(jsonPath("$.costBasis.amount").value("374662.6300"))
                .andExpect(jsonPath("$.cashBalance.amount").value("25000.0000"))
                // totalValue = marketValue + cashBalance (§11)
                .andExpect(jsonPath("$.totalValue.amount").value("414442.1000"))
                .andExpect(jsonPath("$.unrealisedPnl.amount").value("14779.4700"))
                .andExpect(jsonPath("$.realisedPnl.amount").value("4120.0000"))
                .andExpect(jsonPath("$.unrealisedPnlPct").value("3.9400"))
                .andExpect(jsonPath("$.holdingCount").value(3))
                .andExpect(jsonPath("$.dataQuality.priceAsOf").value("2026-06-30"))
                .andExpect(jsonPath("$.dataQuality.rateAsOf").value("2026-06-30"))
                .andExpect(jsonPath("$.dataQuality.stale").value(false));
    }

    @Test
    void shouldDefaultAsOfToTodayInUtc() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), isNull()))
                .willReturn(populated(today));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation"))
                .andExpect(status().isOk());

        verify(valuationService).valuate(USER.id(), PORTFOLIO_ID, today, null);
    }

    @Test
    void shouldPassTheCurrencyOverrideThrough() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), eq(CurrencyCode.USD)))
                .willReturn(new ValuationResult(PORTFOLIO_ID, today, CurrencyCode.USD,
                        new BigDecimal("4476.35"), new BigDecimal("4306.47"), new BigDecimal("287.36"),
                        new BigDecimal("4763.71"), new BigDecimal("169.88"), BigDecimal.ZERO,
                        new BigDecimal("3.9400"), 3, today, today, false));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.marketValue.currency").value("USD"));

        verify(valuationService).valuate(USER.id(), PORTFOLIO_ID, today, CurrencyCode.USD);
    }

    @Test
    void shouldReturnZerosAndANullPercentageForAnEmptyPortfolioNeverA404() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), isNull())).willReturn(
                new ValuationResult(PORTFOLIO_ID, today, CurrencyCode.INR,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, null, 0, null, null, false));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketValue.amount").value("0.0000"))
                .andExpect(jsonPath("$.totalValue.amount").value("0.0000"))
                .andExpect(jsonPath("$.holdingCount").value(0))
                // zero cost basis means a percentage would divide by zero (TEST_PLAN.md §4.2)
                .andExpect(jsonPath("$.unrealisedPnlPct").value(nullValue()))
                .andExpect(jsonPath("$.dataQuality.stale").value(false));
    }

    /**
     * TEST_PLAN.md §4.2: "JSON assertion that the field is literally {@code null}, not absent
     * and not {@code "NaN"}."
     *
     * <p>This is a separate test from the one above on purpose. {@code jsonPath(...).value(
     * nullValue())} passes for an <b>absent</b> field just as happily as for an explicit
     * {@code null}, so it cannot tell the two apart — and a client reading
     * {@code response.unrealisedPnlPct} needs the key to be there. Only the raw body can prove it.
     */
    @Test
    void shouldSerialiseAZeroCostBasisPercentageAsAnExplicitJsonNull() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), isNull())).willReturn(
                new ValuationResult(PORTFOLIO_ID, today, CurrencyCode.INR,
                        new BigDecimal("500.0000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("500.0000"),
                        new BigDecimal("500.0000"), BigDecimal.ZERO, null, 1, today, today, false));

        String body = mockMvc.perform(get("/api/v1/portfolios/7/valuation"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"unrealisedPnlPct\":null");
        assertThat(body).doesNotContain("\"unrealisedPnlPct\":\"null\"");
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
    }

    @Test
    void shouldKeepMarketValueNullWhenNothingCouldBePriced() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), isNull())).willReturn(
                new ValuationResult(PORTFOLIO_ID, today, CurrencyCode.INR,
                        null, new BigDecimal("338300.00"), BigDecimal.ZERO, null,
                        null, BigDecimal.ZERO, null, 3, null, null, true));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation"))
                .andExpect(status().isOk())
                // null means "unpriced", and zero would report the portfolio as worthless instead
                .andExpect(jsonPath("$.marketValue").value(nullValue()))
                .andExpect(jsonPath("$.totalValue").value(nullValue()))
                .andExpect(jsonPath("$.unrealisedPnl").value(nullValue()))
                .andExpect(jsonPath("$.costBasis.amount").value("338300.0000"))
                .andExpect(jsonPath("$.dataQuality.stale").value(true));
    }

    @Test
    void shouldReturn400WhenAsOfIsBeforeTheFirstTransaction() throws Exception {
        given(valuationService.valuate(eq(USER.id()), eq(PORTFOLIO_ID), any(), isNull()))
                .willThrow(new ValidationException("invalid-date-range",
                        "asOf 2020-01-01 is before the portfolio's first transaction on 2026-05-01."));

        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("asOf", "2020-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-date-range"));
    }

    @Test
    void shouldReturn400ForAMalformedAsOf() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("asOf", "30-06-2026"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    @Test
    void shouldReturn400ForAnUnknownCurrency() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/valuation").param("currency", "XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    @Test
    void shouldReturn404NotA403ForAnotherUsersPortfolio() throws Exception {
        given(valuationService.valuate(eq(USER.id()), eq(8L), any(), isNull()))
                .willThrow(new NotFoundException("portfolio", 8L));

        mockMvc.perform(get("/api/v1/portfolios/8/valuation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }
}
