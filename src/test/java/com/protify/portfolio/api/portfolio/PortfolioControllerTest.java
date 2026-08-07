package com.protify.portfolio.api.portfolio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.mapper.PortfolioMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-2-dev-C.md D2-C2: happy path, validation failure, not found and (where a real security
 * filter chain would produce it) unauthorised, for each of the four endpoints, plus the named
 * extras (duplicate name -> 409, blank name -> 400 field=name, unknown currency -> 400, empty
 * list -> 200 [], empty portfolio -> unrealisedPnlPct null not 500).
 *
 * <p>"Unauthorised" per endpoint isn't separately exercised here: there is still no security
 * filter chain in this codebase (Dev B's {@code SecurityConfig} hasn't landed), so a MockMvc
 * request has no mechanism to produce a genuine 401. {@code GlobalExceptionHandlerTest} already
 * covers the 401 shape once an {@code AuthenticationException} is thrown, which is what will
 * exercise this controller for real once the filter chain exists.
 */
@WebMvcTest(controllers = PortfolioController.class)
@Import(PortfolioMapper.class)
class PortfolioControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PortfolioService portfolioService;

    @MockitoBean
    private PortfolioSummaryProvider summaryProvider;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    private static Portfolio portfolio(long id, String name, CurrencyCode currency) {
        return new Portfolio(id, USER.id(), name, currency,
                Instant.parse("2026-02-14T11:03:00Z"), Instant.parse("2026-07-28T10:15:00Z"));
    }

    private static PortfolioSummary emptySummary() {
        return new PortfolioSummary(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, null, DataQualityDto.empty());
    }

    private void stubCurrentUser() {
        given(currentUserResolver.resolve()).willReturn(USER);
    }

    // ---- GET /portfolios ----

    @Test
    void list_happyPath_returnsPortfolios() throws Exception {
        stubCurrentUser();
        Portfolio growth = portfolio(7L, "Growth", CurrencyCode.INR);
        given(portfolioService.findAllByUser(USER.id())).willReturn(List.of(growth));
        given(summaryProvider.summarize(growth)).willReturn(new PortfolioSummary(
                3, new BigDecimal("412873.54"), new BigDecimal("374662.63"), new BigDecimal("25000.00"),
                new BigDecimal("38210.91"), new BigDecimal("4120.00"), "10.2100", DataQualityDto.empty()));

        mockMvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].name").value("Growth"))
                .andExpect(jsonPath("$[0].holdingCount").value(3))
                .andExpect(jsonPath("$[0].marketValue.amount").value("412873.5400"))
                .andExpect(jsonPath("$[0].unrealisedPnlPct").value("10.2100"));
    }

    @Test
    void list_empty_returns200EmptyArrayNeverA404() throws Exception {
        stubCurrentUser();
        given(portfolioService.findAllByUser(USER.id())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void list_amountsAreJsonStringsNotNumbers() throws Exception {
        stubCurrentUser();
        Portfolio p = portfolio(1L, "Solo", CurrencyCode.USD);
        given(portfolioService.findAllByUser(USER.id())).willReturn(List.of(p));
        given(summaryProvider.summarize(p)).willReturn(emptySummary());

        mockMvc.perform(get("/api/v1/portfolios"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"amount\":\"0.0000\"")));
    }

    // ---- POST /portfolios ----

    @Test
    void create_happyPath_returns201WithLocationHeader() throws Exception {
        stubCurrentUser();
        Portfolio created = portfolio(7L, "Growth", CurrencyCode.INR);
        given(portfolioService.create(USER.id(), "Growth", CurrencyCode.INR)).willReturn(created);
        given(summaryProvider.summarize(created)).willReturn(emptySummary());

        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Growth\",\"baseCurrency\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/v1/portfolios/7")))
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.baseCurrency").value("INR"));
    }

    @Test
    void create_blankName_returns400WithFieldNameError() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\",\"baseCurrency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void create_nameTooLong_returns400() throws Exception {
        String longName = "x".repeat(121);
        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + longName + "\",\"baseCurrency\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void create_missingBaseCurrency_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Growth\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("baseCurrency"));
    }

    @Test
    void create_unknownCurrency_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Growth\",\"baseCurrency\":\"XYZ\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_duplicateName_returns409() throws Exception {
        stubCurrentUser();
        given(portfolioService.create(eq(USER.id()), eq("Growth"), any()))
                .willThrow(new DuplicateKeyException("uk_portfolio_user_name"));

        mockMvc.perform(post("/api/v1/portfolios")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Growth\",\"baseCurrency\":\"INR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/duplicate-portfolio-name"));
    }

    // ---- GET /portfolios/{id} ----

    @Test
    void get_happyPath_returns200WithDetailFields() throws Exception {
        stubCurrentUser();
        Portfolio p = portfolio(7L, "Growth", CurrencyCode.INR);
        given(portfolioService.getOrThrow(USER.id(), 7L)).willReturn(p);
        given(summaryProvider.summarize(p)).willReturn(new PortfolioSummary(
                3, new BigDecimal("412873.54"), new BigDecimal("374662.63"), new BigDecimal("25000.00"),
                new BigDecimal("38210.91"), new BigDecimal("4120.00"), "10.2100", DataQualityDto.empty()));

        mockMvc.perform(get("/api/v1/portfolios/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.costBasis.amount").value("374662.6300"))
                .andExpect(jsonPath("$.cashBalance.amount").value("25000.0000"))
                .andExpect(jsonPath("$.realisedPnl.amount").value("4120.0000"));
    }

    @Test
    void get_anotherUsersOrMissingPortfolio_returns404NeverA403() throws Exception {
        stubCurrentUser();
        given(portfolioService.getOrThrow(USER.id(), 999L)).willThrow(new NotFoundException("portfolio", 999L));

        mockMvc.perform(get("/api/v1/portfolios/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }

    @Test
    void get_emptyPortfolio_unrealisedPnlPctIsNullNotA500() throws Exception {
        stubCurrentUser();
        Portfolio p = portfolio(9L, "New", CurrencyCode.USD);
        given(portfolioService.getOrThrow(USER.id(), 9L)).willReturn(p);
        given(summaryProvider.summarize(p)).willReturn(emptySummary());

        mockMvc.perform(get("/api/v1/portfolios/9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unrealisedPnlPct").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.holdingCount").value(0));
    }

    @Test
    void get_nonNumericId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    // ---- PATCH /portfolios/{id} (day-4-dev-C.md D4-C4) ----

    @Test
    void patch_happyPath_updatesNameAndBaseCurrency() throws Exception {
        stubCurrentUser();
        Portfolio updated = portfolio(7L, "Growth Fund", CurrencyCode.USD);
        given(portfolioService.update(USER.id(), 7L, "Growth Fund", CurrencyCode.USD)).willReturn(updated);
        given(summaryProvider.summarize(updated)).willReturn(emptySummary());

        mockMvc.perform(patch("/api/v1/portfolios/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Growth Fund\",\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Growth Fund"))
                .andExpect(jsonPath("$.baseCurrency").value("USD"));
    }

    @Test
    void patch_onlyBaseCurrency_leavesNameUnspecifiedToTheService() throws Exception {
        stubCurrentUser();
        Portfolio updated = portfolio(7L, "Growth", CurrencyCode.USD);
        given(portfolioService.update(USER.id(), 7L, null, CurrencyCode.USD)).willReturn(updated);
        given(summaryProvider.summarize(updated)).willReturn(emptySummary());

        mockMvc.perform(patch("/api/v1/portfolios/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"USD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseCurrency").value("USD"));

        verify(portfolioService).update(USER.id(), 7L, null, CurrencyCode.USD);
    }

    @Test
    void patch_neitherFieldProvided_returns400() throws Exception {
        stubCurrentUser();

        mockMvc.perform(patch("/api/v1/portfolios/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(portfolioService, org.mockito.Mockito.never()).update(anyLong(), anyLong(), any(), any());
    }

    @Test
    void patch_anotherUsersOrMissingPortfolio_returns404() throws Exception {
        stubCurrentUser();
        given(portfolioService.update(eq(USER.id()), eq(999L), any(), any()))
                .willThrow(new NotFoundException("portfolio", 999L));

        mockMvc.perform(patch("/api/v1/portfolios/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }

    @Test
    void patch_duplicateName_returns409() throws Exception {
        stubCurrentUser();
        given(portfolioService.update(eq(USER.id()), eq(7L), eq("Taken"), isNull()))
                .willThrow(new DuplicateKeyException("uk_portfolio_user_name"));

        mockMvc.perform(patch("/api/v1/portfolios/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Taken\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/duplicate-portfolio-name"));
    }

    @Test
    void patch_unknownCurrency_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/portfolios/7")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseCurrency\":\"XYZ\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- DELETE /portfolios/{id} ----

    @Test
    void delete_happyPath_returns204() throws Exception {
        stubCurrentUser();

        mockMvc.perform(delete("/api/v1/portfolios/7"))
                .andExpect(status().isNoContent());

        verify(portfolioService).delete(USER.id(), 7L);
    }

    @Test
    void delete_anotherUsersOrMissingPortfolio_returns404() throws Exception {
        stubCurrentUser();
        org.mockito.Mockito.doThrow(new NotFoundException("portfolio", 999L))
                .when(portfolioService).delete(USER.id(), 999L);

        mockMvc.perform(delete("/api/v1/portfolios/999"))
                .andExpect(status().isNotFound());
    }

    // ---- cross-cutting ----

    @Test
    void everyServiceCallUsesTheResolvedUserIdNeverAnUnscopedQuery() throws Exception {
        stubCurrentUser();
        given(portfolioService.findAllByUser(anyLong())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/portfolios")).andExpect(status().isOk());

        verify(portfolioService).findAllByUser(USER.id());
    }
}
