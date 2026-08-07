package com.protify.portfolio.api.holding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.protify.portfolio.api.mapper.HoldingMapper;
import com.protify.portfolio.api.mapper.InstrumentMapper;
import com.protify.portfolio.api.user.CurrentUser;
import com.protify.portfolio.api.user.CurrentUserResolver;
import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.enums.TransactionType;
import com.protify.portfolio.common.error.NotFoundException;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.holding.Holding;
import com.protify.portfolio.holding.HoldingRepository;
import com.protify.portfolio.instrument.Instrument;
import com.protify.portfolio.instrument.InstrumentRepository;
import com.protify.portfolio.marketdata.CachingMarketDataService;
import com.protify.portfolio.marketdata.PriceResult;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.transaction.TransactionRepository;
import com.protify.portfolio.transaction.Txn;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * day-3-dev-C.md D3-C2, API_CONTRACT.md §10. The real {@link HoldingViewService},
 * {@link HoldingMapper} and {@link InstrumentMapper} run; only the repositories and Dev B's
 * caching services are stubbed. The point of this endpoint is which currency each field is in,
 * and mocking the code that decides that would assert nothing.
 *
 * <p>The fixture is the demo portfolio from the Day 3 hand-off: AAPL in USD, RELIANCE in INR and
 * SHEL in GBP, all totalled in INR.
 */
@WebMvcTest(controllers = HoldingController.class)
@Import({HoldingViewService.class, HoldingMapper.class, InstrumentMapper.class})
class HoldingControllerTest {

    private static final CurrentUser USER = new CurrentUser(42L, "dhruv@example.com", "Dhruv", null, Instant.EPOCH);
    private static final long PORTFOLIO_ID = 7L;

    private static final Instrument AAPL = new Instrument(
            1L, "AAPL", "Apple Inc.", AssetType.STOCK, CurrencyCode.USD, "NASDAQ", "Technology", Instant.EPOCH);
    private static final Instrument RELIANCE = new Instrument(
            2L, "RELIANCE", "Reliance Industries Ltd", AssetType.STOCK, CurrencyCode.INR, "NSE", "Energy", Instant.EPOCH);
    private static final Instrument SHEL = new Instrument(
            3L, "SHEL", "Shell plc", AssetType.STOCK, CurrencyCode.GBP, "LSE", "Energy", Instant.EPOCH);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PortfolioService portfolioService;

    @MockitoBean
    private HoldingRepository holdingRepository;

    @MockitoBean
    private TransactionRepository transactionRepository;

    @MockitoBean
    private InstrumentRepository instrumentRepository;

    @MockitoBean
    private CachingMarketDataService marketDataService;

    @MockitoBean
    private CachingFxRateService fxRateService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @BeforeEach
    void stubOwnedInrPortfolio() {
        given(currentUserResolver.resolve()).willReturn(USER);
        given(portfolioService.getOrThrow(USER.id(), PORTFOLIO_ID)).willReturn(new Portfolio(
                PORTFOLIO_ID, USER.id(), "Growth", CurrencyCode.INR,
                Instant.parse("2026-02-14T11:03:00Z"), Instant.parse("2026-07-28T10:15:00Z")));
        for (Instrument instrument : List.of(AAPL, RELIANCE, SHEL)) {
            given(instrumentRepository.findById(instrument.id())).willReturn(Optional.of(instrument));
        }
        givenRate(CurrencyCode.INR, CurrencyCode.INR, "1");
        givenRate(CurrencyCode.USD, CurrencyCode.USD, "1");
    }

    private void givenRate(CurrencyCode from, CurrencyCode to, String rate) {
        given(fxRateService.rate(eq(from), eq(to), any(LocalDate.class))).willAnswer(invocation -> Optional.of(
                new FxQuote(from, to, invocation.getArgument(2), new BigDecimal(rate), FxSource.MANUAL)));
    }

    private void givenPrice(Instrument instrument, String price) {
        given(marketDataService.priceFor(eq(instrument.id()), any(LocalDate.class))).willAnswer(invocation ->
                Optional.of(new PriceResult(new BigDecimal(price), instrument.currency(),
                        invocation.getArgument(1), PriceSource.YAHOO)));
    }

    private static Holding holding(long id, Instrument instrument, String quantity, String avgCost) {
        return new Holding(id, PORTFOLIO_ID, instrument.id(), new BigDecimal(quantity),
                new BigDecimal(avgCost), BigDecimal.ZERO, Instant.EPOCH);
    }

    private static Txn buy(long id, Instrument instrument, String quantity, String price, String executedAt) {
        return new Txn(id, PORTFOLIO_ID, instrument.id(), TransactionType.BUY, new BigDecimal(quantity),
                new BigDecimal(price), BigDecimal.ZERO, instrument.currency(), Instant.parse(executedAt),
                null, Instant.EPOCH);
    }

    /**
     * 12 AAPL at $200, 10 RELIANCE at ₹1450, 40 SHEL at £25. With USD→INR 87 and GBP→INR 115 the
     * INR cost basis is 208 800 + 14 500 + 115 000 = 338 300.
     */
    private void givenThreeCurrencyPortfolio() {
        given(holdingRepository.findByPortfolio(PORTFOLIO_ID)).willReturn(List.of(
                holding(101L, AAPL, "12", "200.00"),
                holding(102L, RELIANCE, "10", "1450.00"),
                holding(103L, SHEL, "40", "25.00")));
        given(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).willReturn(List.of(
                buy(1L, SHEL, "40", "25.00", "2026-05-01T10:00:00Z"),
                buy(2L, AAPL, "12", "200.00", "2026-06-11T14:30:00Z"),
                buy(3L, RELIANCE, "10", "1450.00", "2026-07-28T10:15:00Z")));
        givenPrice(AAPL, "228.10");
        givenPrice(RELIANCE, "1489.60");
        givenPrice(SHEL, "29.15");
        givenRate(CurrencyCode.USD, CurrencyCode.INR, "87.00000000");
        givenRate(CurrencyCode.GBP, CurrencyCode.INR, "115.00000000");
    }

    @Test
    void shouldRenderAvgCostNativelyAndEveryTotalInTheBaseCurrency() throws Exception {
        givenThreeCurrencyPortfolio();

        mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))

                // AAPL: avgCost and lastPrice stay USD, every total is INR
                .andExpect(jsonPath("$[0].instrument.symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].quantity").value("12.000000"))
                .andExpect(jsonPath("$[0].avgCost.amount").value("200.0000"))
                .andExpect(jsonPath("$[0].avgCost.currency").value("USD"))
                .andExpect(jsonPath("$[0].lastPrice.amount").value("228.1000"))
                .andExpect(jsonPath("$[0].lastPrice.currency").value("USD"))
                // 12 x 228.10 x 87
                .andExpect(jsonPath("$[0].marketValue.amount").value("238136.4000"))
                .andExpect(jsonPath("$[0].marketValue.currency").value("INR"))
                .andExpect(jsonPath("$[0].costBasis.amount").value("208800.0000"))
                .andExpect(jsonPath("$[0].costBasis.currency").value("INR"))
                .andExpect(jsonPath("$[0].unrealisedPnl.amount").value("29336.4000"))
                .andExpect(jsonPath("$[0].unrealisedPnlPct").value("14.0500"))
                .andExpect(jsonPath("$[0].fxRate").value("87.00000000"))

                // RELIANCE trades in the base currency, so there is no rate to report
                .andExpect(jsonPath("$[1].avgCost.currency").value("INR"))
                .andExpect(jsonPath("$[1].marketValue.amount").value("14896.0000"))
                .andExpect(jsonPath("$[1].unrealisedPnl.amount").value("396.0000"))
                .andExpect(jsonPath("$[1].fxRate").value(nullValue()))

                // SHEL: avgCost in GBP, 40 x 29.15 x 115 in INR
                .andExpect(jsonPath("$[2].avgCost.currency").value("GBP"))
                .andExpect(jsonPath("$[2].marketValue.amount").value("134090.0000"))
                .andExpect(jsonPath("$[2].marketValue.currency").value("INR"))
                .andExpect(jsonPath("$[2].unrealisedPnl.amount").value("19090.0000"))
                .andExpect(jsonPath("$[2].unrealisedPnlPct").value("16.6000"))
                .andExpect(jsonPath("$[2].fxRate").value("115.00000000"));
    }

    @Test
    void shouldReExpressEveryTotalWhenCurrencyIsOverriddenButLeaveAvgCostNative() throws Exception {
        givenThreeCurrencyPortfolio();
        givenRate(CurrencyCode.INR, CurrencyCode.USD, "0.01000000");
        givenRate(CurrencyCode.GBP, CurrencyCode.USD, "1.30000000");

        mockMvc.perform(get("/api/v1/portfolios/7/holdings").param("currency", "USD"))
                .andExpect(status().isOk())

                // avgCost is stored native and never converted, whatever ?currency= says
                .andExpect(jsonPath("$[0].avgCost.amount").value("200.0000"))
                .andExpect(jsonPath("$[0].avgCost.currency").value("USD"))
                .andExpect(jsonPath("$[1].avgCost.amount").value("1450.0000"))
                .andExpect(jsonPath("$[1].avgCost.currency").value("INR"))
                .andExpect(jsonPath("$[2].avgCost.amount").value("25.0000"))
                .andExpect(jsonPath("$[2].avgCost.currency").value("GBP"))

                // ... while every total is now USD
                .andExpect(jsonPath("$[0].marketValue.amount").value("2737.2000"))
                .andExpect(jsonPath("$[0].marketValue.currency").value("USD"))
                .andExpect(jsonPath("$[0].costBasis.amount").value("2400.0000"))
                .andExpect(jsonPath("$[0].fxRate").value(nullValue()))
                .andExpect(jsonPath("$[1].marketValue.amount").value("148.9600"))
                .andExpect(jsonPath("$[1].marketValue.currency").value("USD"))
                .andExpect(jsonPath("$[1].costBasis.amount").value("145.0000"))
                .andExpect(jsonPath("$[1].fxRate").value("0.01000000"))
                .andExpect(jsonPath("$[2].marketValue.amount").value("1515.8000"))
                .andExpect(jsonPath("$[2].costBasis.amount").value("1300.0000"))
                .andExpect(jsonPath("$[2].fxRate").value("1.30000000"));
    }

    @Test
    void shouldSumWeightsTo100() throws Exception {
        givenThreeCurrencyPortfolio();

        String body = mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        BigDecimal total = BigDecimal.ZERO;
        for (JsonNode row : objectMapper.readTree(body)) {
            total = total.add(new BigDecimal(row.get("weightPct").asText()));
        }
        assertThat(total.subtract(new BigDecimal("100")).abs())
                .isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void shouldHideClosedPositionsByDefaultButShowTheirRealisedPnlWhenIncludeZeroIsTrue() throws Exception {
        given(holdingRepository.findByPortfolio(PORTFOLIO_ID))
                .willReturn(List.of(holding(101L, AAPL, "0", "200.00")));
        given(transactionRepository.findByPortfolioOrderByExecutedAt(PORTFOLIO_ID)).willReturn(List.of(
                buy(1L, AAPL, "12", "200.00", "2026-06-11T14:30:00Z"),
                new Txn(2L, PORTFOLIO_ID, AAPL.id(), TransactionType.SELL, new BigDecimal("12"),
                        new BigDecimal("228.10"), BigDecimal.ZERO, CurrencyCode.USD,
                        Instant.parse("2026-07-30T09:00:00Z"), null, Instant.EPOCH)));
        givenPrice(AAPL, "228.10");
        givenRate(CurrencyCode.USD, CurrencyCode.INR, "87.00000000");

        mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));

        mockMvc.perform(get("/api/v1/portfolios/7/holdings").param("includeZero", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].quantity").value("0.000000"))
                // 12 x 228.10 x 87 realised against a 208 800 cost basis
                .andExpect(jsonPath("$[0].realisedPnl.amount").value("29336.4000"))
                .andExpect(jsonPath("$[0].realisedPnl.currency").value("INR"))
                .andExpect(jsonPath("$[0].marketValue.amount").value("0.0000"))
                // cost basis of zero means a percentage would be a divide-by-zero (§4.2)
                .andExpect(jsonPath("$[0].unrealisedPnlPct").value(nullValue()))
                .andExpect(jsonPath("$[0].weightPct").value("0.0000"));
    }

    @Test
    void shouldReturn200AndAnEmptyArrayForAnEmptyPortfolioNeverA404() throws Exception {
        given(holdingRepository.findByPortfolio(PORTFOLIO_ID)).willReturn(List.of());

        mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void shouldStillReturn200WithNullMoneyAndAStaleFlagWhenAPositionCannotBePriced() throws Exception {
        givenThreeCurrencyPortfolio();
        // The provider, the cache and the seed data all have nothing for AAPL.
        given(marketDataService.priceFor(eq(AAPL.id()), any(LocalDate.class))).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].lastPrice").value(nullValue()))
                .andExpect(jsonPath("$[0].marketValue").value(nullValue()))
                .andExpect(jsonPath("$[0].unrealisedPnl").value(nullValue()))
                // the cost basis is still known — it does not depend on a price
                .andExpect(jsonPath("$[0].costBasis.amount").value("208800.0000"))
                .andExpect(jsonPath("$[0].dataQuality.stale").value(true))
                // one unpriceable instrument must not blank the rest of the portfolio
                .andExpect(jsonPath("$[1].marketValue.amount").value("14896.0000"))
                .andExpect(jsonPath("$[1].dataQuality.stale").value(false));
    }

    @Test
    void shouldReportDataQualityDatesOnEveryRow() throws Exception {
        givenThreeCurrencyPortfolio();

        mockMvc.perform(get("/api/v1/portfolios/7/holdings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dataQuality.priceAsOf").isNotEmpty())
                .andExpect(jsonPath("$[0].dataQuality.rateAsOf").isNotEmpty())
                .andExpect(jsonPath("$[0].dataQuality.stale").value(false));
    }

    @Test
    void shouldReturn400ForAnUnknownCurrency() throws Exception {
        mockMvc.perform(get("/api/v1/portfolios/7/holdings").param("currency", "XYZ"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/invalid-parameter"));
    }

    @Test
    void shouldReturn404NotA403ForAnotherUsersPortfolio() throws Exception {
        given(portfolioService.getOrThrow(USER.id(), 8L)).willThrow(new NotFoundException("portfolio", 8L));

        mockMvc.perform(get("/api/v1/portfolios/8/holdings"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://portfolio.local/errors/portfolio-not-found"));

        org.mockito.Mockito.verify(holdingRepository, org.mockito.Mockito.never()).findByPortfolio(anyLong());
    }
}
