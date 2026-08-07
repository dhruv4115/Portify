package com.protify.portfolio.api.instrument;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.protify.portfolio.api.mapper.InstrumentMapper;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-2-dev-C.md D2-C3. "No token -> 401" isn't exercised here for the same reason as
 * {@code PortfolioControllerTest} - no security filter chain exists yet; the 401 shape is
 * covered by {@code GlobalExceptionHandlerTest}.
 */
@WebMvcTest(controllers = InstrumentController.class)
@Import(InstrumentMapper.class)
class InstrumentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private InstrumentRepository instrumentRepository;

    private static Instrument instrument(long id, String symbol, String name) {
        return new Instrument(id, symbol, name, AssetType.STOCK, CurrencyCode.INR, "NSE", "Energy", Instant.EPOCH);
    }

    @Test
    void searchForRel_returnsRelianceFirst() throws Exception {
        given(instrumentRepository.search(eq("rel"), isNull(), isNull(), anyInt())).willReturn(List.of(
                instrument(12L, "RELIANCE", "Reliance Industries Ltd"),
                instrument(31L, "RELIGARE", "Religare Enterprises Ltd")));

        mockMvc.perform(get("/api/v1/instruments").param("query", "rel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("RELIANCE"))
                .andExpect(jsonPath("$[1].symbol").value("RELIGARE"));
    }

    @Test
    void blankQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/instruments").param("query", "  "))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingQuery_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/instruments"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noMatch_returns200EmptyArrayNotAnError() throws Exception {
        given(instrumentRepository.search(eq("zzzzz"), isNull(), isNull(), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/instruments").param("query", "zzzzz"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void limitOver50_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/instruments").param("query", "a").param("limit", "51"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void limitAt50_isAccepted() throws Exception {
        given(instrumentRepository.search(eq("a"), isNull(), isNull(), eq(50))).willReturn(List.of());

        mockMvc.perform(get("/api/v1/instruments").param("query", "a").param("limit", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void defaultLimitIs20() throws Exception {
        given(instrumentRepository.search(eq("a"), isNull(), isNull(), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/instruments").param("query", "a"))
                .andExpect(status().isOk());

        verify(instrumentRepository).search(eq("a"), isNull(), isNull(), eq(20));
    }

    @Test
    void assetTypeAndCurrencyFiltersArePassedThrough() throws Exception {
        given(instrumentRepository.search(any(), any(), any(), anyInt())).willReturn(List.of());

        mockMvc.perform(get("/api/v1/instruments")
                        .param("query", "a")
                        .param("assetType", "ETF")
                        .param("currency", "USD"))
                .andExpect(status().isOk());

        verify(instrumentRepository).search("a", AssetType.ETF, CurrencyCode.USD, 20);
    }
}
