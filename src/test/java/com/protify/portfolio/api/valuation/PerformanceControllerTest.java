package com.protify.portfolio.api.valuation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.PerformanceMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.valuation.PerformancePoint;
import com.protify.portfolio.valuation.PerformanceResult;
import com.protify.portfolio.valuation.PerformanceService;
import com.protify.portfolio.valuation.PerformanceSummary;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-3-dev-C.md D3-C3, API_CONTRACT.md §12. Dev A's {@link PerformanceService} is stubbed — the
 * series maths is theirs and {@code PerformanceServiceTest} covers it. What is tested here is the
 * contract surface: the defaults, one point per interval, the two range rejections naming a
 * field, and an empty portfolio producing a valid summary rather than a divide-by-zero.
 *
 * <p>"No token -> 401" lives in {@code ReadEndpointSecurityTest}, which loads the real filter
 * chain this slice deliberately omits.
 */
@WebMvcTest(controllers = PerformanceController.class)
@Import({PerformanceViewService.class, PerformanceMapper.class})
class PerformanceControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);
    private static final long PORTFOLIO_ID = 7L;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PerformanceService performanceService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @BeforeEach
    void stubCurrentUser() {
        given(currentUserResolver.resolve()).willReturn(USER);
    }

    /** One point per day over {@code [from, to]}, each worth {@code 1000 + n}. */
    private static PerformanceResult daily(LocalDate from, LocalDate to) {
        List<PerformancePoint> points = new ArrayList<>();
        int n = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            BigDecimal total = new BigDecimal(1000 + n++);
            points.add(new PerformancePoint(day, total, new BigDecimal("900"), BigDecimal.ZERO,
                    total, total.subtract(new BigDecimal("900")), false));
        }
        BigDecimal start = points.get(0).totalValue();
        BigDecimal end = points.get(points.size() - 1).totalValue();
        PerformanceSummary summary = new PerformanceSummary(start, end, end.subtract(start),
                new BigDecimal("0.5000"), BigDecimal.ZERO);
        return new PerformanceResult(PORTFOLIO_ID, CurrencyCode.INR, from, to, points, summary,
                to, to, false);
    }

    @Test
    void shouldReturnOnePointPerDay() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 24);
        LocalDate to = LocalDate.of(2026, 7, 29);
        given(performanceService.getPerformance(USER.id(), PORTFOLIO_ID, from, to, null))
                .willReturn(daily(from, to));

        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2026-07-24").param("to", "2026-07-29"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.portfolioId").value(7))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.from").value("2026-07-24"))
                .andExpect(jsonPath("$.to").value("2026-07-29"))
                .andExpect(jsonPath("$.interval").value("DAILY"))
                .andExpect(jsonPath("$.points", hasSize(6)))
                .andExpect(jsonPath("$.points[0].date").value("2026-07-24"))
                .andExpect(jsonPath("$.points[0].totalValue.amount").value("1000.0000"))
                .andExpect(jsonPath("$.points[0].totalValue.currency").value("INR"))
                .andExpect(jsonPath("$.points[0].filled").value(false))
                .andExpect(jsonPath("$.points[5].date").value("2026-07-29"))
                .andExpect(jsonPath("$.points[5].totalValue.amount").value("1005.0000"))
                .andExpect(jsonPath("$.summary.startValue.amount").value("1000.0000"))
                .andExpect(jsonPath("$.summary.endValue.amount").value("1005.0000"))
                .andExpect(jsonPath("$.summary.absoluteChange.amount").value("5.0000"))
                .andExpect(jsonPath("$.summary.percentChange").value("0.5000"))
                .andExpect(jsonPath("$.summary.netContributions.amount").value("0.0000"))
                .andExpect(jsonPath("$.dataQuality.priceAsOf").value("2026-07-29"))
                .andExpect(jsonPath("$.dataQuality.stale").value(false));
    }

    @Test
    void shouldDefaultToOneYearBackFromTodayInUtcAtDailyGranularity() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate yearAgo = today.minusYears(1);
        given(performanceService.getPerformance(eq(USER.id()), eq(PORTFOLIO_ID), any(), any(), isNull()))
                .willReturn(daily(today.minusDays(2), today));

        mockMvc.perform(get("/api/v1/portfolios/7/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("DAILY"));

        verify(performanceService).getPerformance(USER.id(), PORTFOLIO_ID, yearAgo, today, null);
    }

    @Test
    void shouldSampleWeeklyAndMonthlySeriesFromTheDailyOne() throws Exception {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 3, 31);
        given(performanceService.getPerformance(eq(USER.id()), eq(PORTFOLIO_ID), eq(from), eq(to), isNull()))
                .willReturn(daily(from, to));

        // 90 days, Jan-Mar, is 3 monthly points, each the last day of its month
        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .param("interval", "MONTHLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("MONTHLY"))
                .andExpect(jsonPath("$.points", hasSize(3)))
                .andExpect(jsonPath("$.points[0].date").value("2026-01-31"))
                .andExpect(jsonPath("$.points[1].date").value("2026-02-28"))
                .andExpect(jsonPath("$.points[2].date").value("2026-03-31"))
                // the summary still spans the whole requested window, not the sampled points
                .andExpect(jsonPath("$.summary.startValue.amount").value("1000.0000"));

        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2026-01-01").param("to", "2026-03-31")
                        .param("interval", "WEEKLY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.interval").value("WEEKLY"))
                // 1 Jan 2026 is a Thursday, so the first bucket closes on Sunday 4 Jan
                .andExpect(jsonPath("$.points[0].date").value("2026-01-04"))
                .andExpect(jsonPath("$.points[1].date").value("2026-01-11"))
                // the window's final day always closes the last bucket
                .andExpect(jsonPath("$.points[13].date").value("2026-03-31"));
    }

    @Test
    void shouldReturn400NamingTheFieldWhenFromIsAfterTo() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2026-07-29").param("to", "2026-07-24"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-date-range"))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be after to"));

        verify(performanceService, never()).getPerformance(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void shouldReturn400WhenTheRangeExceedsFiveYears() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2020-01-01").param("to", "2026-07-29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-date-range"))
                .andExpect(jsonPath("$.errors[0].field").value("to"));

        verify(performanceService, never()).getPerformance(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void shouldAcceptARangeOfExactlyFiveYears() throws Exception {
        LocalDate from = LocalDate.of(2021, 7, 29);
        LocalDate to = LocalDate.of(2026, 7, 29);
        given(performanceService.getPerformance(USER.id(), PORTFOLIO_ID, from, to, null))
                .willReturn(daily(to.minusDays(1), to));

        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2021-07-29").param("to", "2026-07-29"))
                .andExpect(status().isOk());
    }

    @Test
    void shouldReturnEmptyPointsAndAValidSummaryForAnEmptyPortfolioNotADivideByZero() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal zero = new BigDecimal("0.0000");
        given(performanceService.getPerformance(eq(USER.id()), eq(PORTFOLIO_ID), any(), any(), isNull()))
                .willReturn(new PerformanceResult(PORTFOLIO_ID, CurrencyCode.INR, today.minusYears(1), today,
                        List.of(), new PerformanceSummary(zero, zero, zero, null, zero), null, null, false));

        mockMvc.perform(get("/api/v1/portfolios/7/performance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(0)))
                .andExpect(jsonPath("$.summary.startValue.amount").value("0.0000"))
                .andExpect(jsonPath("$.summary.absoluteChange.amount").value("0.0000"))
                // a window that opened at zero has no baseline to have changed from (§4.2)
                .andExpect(jsonPath("$.summary.percentChange").value(nullValue()))
                .andExpect(jsonPath("$.dataQuality.priceAsOf").value(nullValue()));
    }

    /**
     * TEST_PLAN.md §4.2's "same rule in {@code PerformanceSummary.percentChange} when
     * {@code startValue} is 0", asserted on the raw body for the same reason as
     * {@code ValuationControllerTest#shouldSerialiseAZeroCostBasisPercentageAsAnExplicitJsonNull}:
     * {@code jsonPath(...).value(nullValue())} cannot distinguish an explicit {@code null} from
     * a missing key, and the chart component reads this field by name.
     */
    @Test
    void shouldSerialiseAZeroStartValuePercentChangeAsAnExplicitJsonNull() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        BigDecimal zero = new BigDecimal("0.0000");
        given(performanceService.getPerformance(eq(USER.id()), eq(PORTFOLIO_ID), any(), any(), isNull()))
                .willReturn(new PerformanceResult(PORTFOLIO_ID, CurrencyCode.INR, today.minusDays(1), today,
                        List.of(), new PerformanceSummary(zero, new BigDecimal("200.0000"),
                                new BigDecimal("200.0000"), null, zero), null, null, false));

        String body = mockMvc.perform(get("/api/v1/portfolios/7/performance"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"percentChange\":null");
        assertThat(body).doesNotContain("\"percentChange\":\"null\"");
        assertThat(body).doesNotContain("NaN").doesNotContain("Infinity");
    }

    @Test
    void shouldPassTheCurrencyOverrideThrough() throws Exception {
        LocalDate from = LocalDate.of(2026, 7, 24);
        LocalDate to = LocalDate.of(2026, 7, 29);
        given(performanceService.getPerformance(USER.id(), PORTFOLIO_ID, from, to, CurrencyCode.USD))
                .willReturn(new PerformanceResult(PORTFOLIO_ID, CurrencyCode.USD, from, to,
                        daily(from, to).points(), daily(from, to).summary(), to, to, false));

        mockMvc.perform(get("/api/v1/portfolios/7/performance")
                        .param("from", "2026-07-24").param("to", "2026-07-29")
                        .param("currency", "USD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.points[0].totalValue.currency").value("USD"));
    }

    @Test
    void shouldReturn400ForAnUnknownInterval() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/performance").param("interval", "HOURLY"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    @Test
    void shouldReturn404NotA403ForAnotherUsersPortfolio() throws Exception {
        given(performanceService.getPerformance(eq(USER.id()), eq(8L), any(), any(), isNull()))
                .willThrow(new NotFoundException("portfolio", 8L));

        mockMvc.perform(get("/api/v1/portfolios/8/performance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }
}
