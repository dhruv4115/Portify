package com.protify.portfolio.api.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.protify.portfolio.api.dto.InsightsResponse;
import com.protify.portfolio.api.holding.HoldingViewService;
import com.protify.portfolio.api.mapper.InsightsMapper;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.insights.Engine;
import com.protify.portfolio.insights.InsightsClient;
import com.protify.portfolio.insights.InsightsResult;
import com.protify.portfolio.insights.PortfolioSummary;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The variants rule (API_CONTRACT.md §18): two readings are offered only when there genuinely
 * are two, and both must describe the same snapshot.
 *
 * <p>{@link InsightsMapper} is the real thing — it is pure, and mocking the class that decides
 * which variant becomes the top-level {@code summary} would assert nothing about the answer.
 */
@ExtendWith(MockitoExtension.class)
class InsightsViewServiceTest {

    private static final long USER_ID = 42L;
    private static final long PORTFOLIO_ID = 7L;

    @Mock
    private PortfolioService portfolioService;
    @Mock
    private ValuationService valuationService;
    @Mock
    private HoldingViewService holdingViewService;
    @Mock
    private InsightsClient insightsClient;

    private InsightsViewService service;

    @BeforeEach
    void setUp() {
        service = new InsightsViewService(portfolioService, valuationService, holdingViewService,
                insightsClient, new InsightsMapper());

        when(portfolioService.getOrThrow(USER_ID, PORTFOLIO_ID)).thenReturn(new Portfolio(
                PORTFOLIO_ID, USER_ID, "Growth", CurrencyCode.USD, Instant.EPOCH, Instant.EPOCH));
        when(valuationService.valuate(anyLong(), anyLong(), any(), any())).thenReturn(new ValuationResult(
                PORTFOLIO_ID, LocalDate.of(2026, 8, 6), CurrencyCode.USD,
                new BigDecimal("10000"), new BigDecimal("9000"), new BigDecimal("500"),
                new BigDecimal("10500"), new BigDecimal("1000"), BigDecimal.ZERO,
                new BigDecimal("11.11"), 2, null, null, false));
        when(holdingViewService.valuations(anyLong(), anyLong(), any())).thenReturn(List.of());
    }

    private void givenGenerated(String summary, Engine engine) {
        when(insightsClient.generate(any(), any(), any()))
                .thenReturn(new InsightsResult(summary, List.of(), engine));
    }

    @Test
    void offersBothReadingsWhenTheModelAnswered() {
        givenGenerated("Up 10.2% against cost.", Engine.LLM);
        when(insightsClient.ruleBased(any()))
                .thenReturn(new InsightsResult("AAPL is 57.7% of market value.", List.of(), Engine.RULE_BASED));

        InsightsResponse response = service.generate(USER_ID, PORTFOLIO_ID, "1M", "concise");

        assertThat(response.variants()).extracting(v -> v.engine())
                .containsExactly("AI_GENERATED", "RULE_BASED");
        // The AI one is what a client shows without asking for a choice.
        assertThat(response.engine()).isEqualTo("AI_GENERATED");
        assertThat(response.summary()).isEqualTo("Up 10.2% against cost.");
    }

    /**
     * The failure path already <em>is</em> the rule-based summary. Pairing it with a freshly
     * computed twin would put a toggle on screen offering a choice between a sentence and
     * itself.
     */
    @Test
    void offersOneReadingWhenTheModelDidNotAnswer() {
        givenGenerated("Nothing notable detected.", Engine.RULE_BASED);

        InsightsResponse response = service.generate(USER_ID, PORTFOLIO_ID, "1M", "concise");

        assertThat(response.variants()).extracting(v -> v.engine()).containsExactly("RULE_BASED");
        assertThat(response.engine()).isEqualTo("RULE_BASED");
        verify(insightsClient, never()).ruleBased(any());
    }

    /**
     * The reason both are generated in one request rather than fetched separately: a toggle
     * whose two sides describe different portfolios would read as the engines disagreeing.
     */
    @Test
    void bothReadingsAreBuiltFromTheSameSnapshot() {
        givenGenerated("Up 10.2% against cost.", Engine.LLM);
        when(insightsClient.ruleBased(any()))
                .thenReturn(new InsightsResult("AAPL is 57.7% of market value.", List.of(), Engine.RULE_BASED));

        service.generate(USER_ID, PORTFOLIO_ID, "1M", "concise");

        ArgumentCaptor<PortfolioSummary> sent = ArgumentCaptor.forClass(PortfolioSummary.class);
        ArgumentCaptor<PortfolioSummary> local = ArgumentCaptor.forClass(PortfolioSummary.class);
        verify(insightsClient).generate(sent.capture(), eq("1M"), eq("concise"));
        verify(insightsClient).ruleBased(local.capture());

        assertThat(local.getValue()).isSameAs(sent.getValue());
        // Only one valuation was taken, so there is no second set of numbers to disagree with.
        verify(valuationService).valuate(eq(USER_ID), eq(PORTFOLIO_ID), any(), eq(CurrencyCode.USD));
    }
}
