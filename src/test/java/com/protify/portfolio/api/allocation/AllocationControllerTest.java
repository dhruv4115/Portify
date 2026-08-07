package com.protify.portfolio.api.allocation;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.dto.AllocationDimension;
import com.protify.portfolio.api.dto.AllocationResponse;
import com.protify.portfolio.api.dto.AllocationSliceDto;
import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.api.dto.MoneyDto;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.error.NotFoundException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = AllocationController.class)
class AllocationControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AllocationService allocationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @Test
    void happyPath_defaultsToAssetTypePerContract() throws Exception {
        given(currentUserResolver.resolve()).willReturn(USER);
        AllocationResponse response = new AllocationResponse(7L, AllocationDimension.ASSET_TYPE, CurrencyCode.INR,
                MoneyDto.of(new BigDecimal("1000"), CurrencyCode.INR),
                List.of(new AllocationSliceDto("STOCK", "STOCK", MoneyDto.of(new BigDecimal("1000"), CurrencyCode.INR),
                        "100.0000", 1)),
                DataQualityDto.empty());
        given(allocationService.compute(USER.id(), 7L, AllocationDimension.ASSET_TYPE, null, null)).willReturn(response);

        mockMvc.perform(get("/api/v1/portfolios/7/allocation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.by").value("ASSET_TYPE"))
                .andExpect(jsonPath("$.slices[0].weightPct").value("100.0000"));
    }

    @Test
    void byCurrency_isPassedThrough() throws Exception {
        given(currentUserResolver.resolve()).willReturn(USER);
        given(allocationService.compute(eq(USER.id()), eq(7L), eq(AllocationDimension.CURRENCY), isNull(), isNull()))
                .willReturn(new AllocationResponse(7L, AllocationDimension.CURRENCY, CurrencyCode.INR,
                        MoneyDto.zero(CurrencyCode.INR), List.of(), DataQualityDto.empty()));

        mockMvc.perform(get("/api/v1/portfolios/7/allocation").param("by", "CURRENCY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.by").value("CURRENCY"));
    }

    @Test
    void anotherUsersOrMissingPortfolio_returns404() throws Exception {
        given(currentUserResolver.resolve()).willReturn(USER);
        given(allocationService.compute(USER.id(), 999L, AllocationDimension.ASSET_TYPE, null, null))
                .willThrow(new NotFoundException("portfolio", 999L));

        mockMvc.perform(get("/api/v1/portfolios/999/allocation"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));
    }

    @Test
    void unknownDimension_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/allocation").param("by", "NOT_A_DIMENSION"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limit_isPassedThrough() throws Exception {
        given(currentUserResolver.resolve()).willReturn(USER);
        given(allocationService.compute(eq(USER.id()), eq(7L), eq(AllocationDimension.SECTOR), isNull(), eq(8)))
                .willReturn(new AllocationResponse(7L, AllocationDimension.SECTOR, CurrencyCode.INR,
                        MoneyDto.zero(CurrencyCode.INR), List.of(), DataQualityDto.empty()));

        mockMvc.perform(get("/api/v1/portfolios/7/allocation").param("by", "SECTOR").param("limit", "8"))
                .andExpect(status().isOk());

        verify(allocationService).compute(USER.id(), 7L, AllocationDimension.SECTOR, null, 8);
    }

    /** A limit of 1 is a single wedge labelled "Other" — a picture of nothing. */
    @Test
    void limitBelowTwo_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/allocation").param("limit", "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void absurdlyLargeLimit_returns400RatherThanAColourWheel() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/allocation").param("limit", "500"))
                .andExpect(status().isBadRequest());
    }
}
