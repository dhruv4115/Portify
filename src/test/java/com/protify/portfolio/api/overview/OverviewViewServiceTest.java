package com.protify.portfolio.api.overview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.protify.portfolio.api.dto.OverviewResponse;
import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.money.Money;
import com.protify.portfolio.fx.CachingFxRateService;
import com.protify.portfolio.fx.ConversionResult;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.portfolio.PortfolioService;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The aggregation rules that make this endpoint worth having: the per-currency subtotals are
 * FX-free arithmetic, the grand total is converted through the dated chain, and a currency with
 * no rate is named rather than dropped.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OverviewViewServiceTest {

    private static final long USER_ID = 42L;
    private static final LocalDate TODAY = LocalDate.now(java.time.ZoneOffset.UTC);

    @Mock
    private PortfolioService portfolioService;

    @Mock
    private ValuationService valuationService;

    @Mock
    private CachingFxRateService fxRateService;

    private OverviewViewService service;

    @BeforeEach
    void setUp() {
        service = new OverviewViewService(portfolioService, valuationService, fxRateService);
    }

    @Test
    void anAccountWithNoPortfoliosIsZeroesAndNotA404() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of());

        OverviewResponse response = service.overview(USER_ID, null);

        assertThat(response.portfolioCount()).isZero();
        assertThat(response.byCurrency()).isEmpty();
        assertThat(response.totalValue().amount()).isEqualTo("0.0000");
        // Nothing was priced because there was nothing to price — that is not staleness.
        assertThat(response.dataQuality().stale()).isFalse();
    }

    @Test
    void oneCurrencyNeedsNoFxRateAtAll() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(
                portfolio(1L, CurrencyCode.INR), portfolio(2L, CurrencyCode.INR)));
        givenValuation(1L, CurrencyCode.INR, "1000", "800");
        givenValuation(2L, CurrencyCode.INR, "500", "400");

        OverviewResponse response = service.overview(USER_ID, null);

        assertThat(response.displayCurrency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.totalValue().amount()).isEqualTo("1500.0000");
        assertThat(response.convertedPortfolioCount()).isEqualTo(2);
        assertThat(response.unconvertedCurrencies()).isEmpty();
        // The whole point of picking the majority currency: no rate was ever needed.
        verify(fxRateService, never()).convert(any(), any(), any());
    }

    @Test
    void subtotalsAreReportedPerCurrencyWithNoFxApplied() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(
                portfolio(1L, CurrencyCode.INR), portfolio(2L, CurrencyCode.USD)));
        givenValuation(1L, CurrencyCode.INR, "1000", "800");
        givenValuation(2L, CurrencyCode.USD, "200", "150");
        givenRate(CurrencyCode.USD, CurrencyCode.INR, "80");

        OverviewResponse response = service.overview(USER_ID, CurrencyCode.INR);

        assertThat(response.byCurrency()).hasSize(2);
        assertThat(response.byCurrency())
                .filteredOn(subtotal -> subtotal.currency() == CurrencyCode.USD)
                .singleElement()
                .satisfies(subtotal -> {
                    // Still dollars — untouched by the INR display choice.
                    assertThat(subtotal.totalValue().amount()).isEqualTo("200.0000");
                    assertThat(subtotal.totalValue().currency()).isEqualTo(CurrencyCode.USD);
                });
    }

    @Test
    void grandTotalConvertsThroughTheDatedFxChain() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(
                portfolio(1L, CurrencyCode.INR), portfolio(2L, CurrencyCode.USD)));
        givenValuation(1L, CurrencyCode.INR, "1000", "800");
        givenValuation(2L, CurrencyCode.USD, "200", "150");
        givenRate(CurrencyCode.USD, CurrencyCode.INR, "80");

        OverviewResponse response = service.overview(USER_ID, CurrencyCode.INR);

        // 1000 INR + (200 USD × 80) = 17000 INR
        assertThat(response.totalValue().amount()).isEqualTo("17000.0000");
        assertThat(response.totalValue().currency()).isEqualTo(CurrencyCode.INR);
        assertThat(response.convertedPortfolioCount()).isEqualTo(2);
    }

    /**
     * The honesty rule. A currency with no rate must not be folded in at a guess, and must not
     * vanish either — the total is smaller, and the response says exactly which portfolios are
     * missing from it and why.
     */
    @Test
    void aCurrencyWithNoRateIsNamedRatherThanSilentlyDroppedFromTheTotal() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(
                portfolio(1L, CurrencyCode.INR), portfolio(2L, CurrencyCode.GBP)));
        givenValuation(1L, CurrencyCode.INR, "1000", "800");
        givenValuation(2L, CurrencyCode.GBP, "500", "400");
        given(fxRateService.convert(any(), eq(CurrencyCode.INR), any())).willReturn(Optional.empty());

        OverviewResponse response = service.overview(USER_ID, CurrencyCode.INR);

        assertThat(response.totalValue().amount()).isEqualTo("1000.0000");
        assertThat(response.portfolioCount()).isEqualTo(2);
        assertThat(response.convertedPortfolioCount()).isEqualTo(1);
        assertThat(response.unconvertedCurrencies()).containsExactly(CurrencyCode.GBP);
        // Excluded from the total, but still fully reported in its own currency.
        assertThat(response.byCurrency())
                .filteredOn(subtotal -> subtotal.currency() == CurrencyCode.GBP)
                .singleElement()
                .satisfies(subtotal -> assertThat(subtotal.totalValue().amount()).isEqualTo("500.0000"));
    }

    @Test
    void theDisplayCurrencyDefaultsToWhicheverMostPortfoliosAlreadyUse() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(
                portfolio(1L, CurrencyCode.USD), portfolio(2L, CurrencyCode.INR), portfolio(3L, CurrencyCode.INR)));
        givenValuation(1L, CurrencyCode.USD, "200", "150");
        givenValuation(2L, CurrencyCode.INR, "1000", "800");
        givenValuation(3L, CurrencyCode.INR, "500", "400");
        givenRate(CurrencyCode.USD, CurrencyCode.INR, "80");

        assertThat(service.overview(USER_ID, null).displayCurrency()).isEqualTo(CurrencyCode.INR);
    }

    @Test
    void anExplicitCurrencyOverridesTheMajorityChoice() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(portfolio(1L, CurrencyCode.INR)));
        givenValuation(1L, CurrencyCode.INR, "1000", "800");
        givenRate(CurrencyCode.INR, CurrencyCode.USD, "0.0125");

        assertThat(service.overview(USER_ID, CurrencyCode.USD).displayCurrency()).isEqualTo(CurrencyCode.USD);
    }

    /** An unpriceable portfolio contributes zero rather than blanking the account, but it does
     * not go unmentioned — its null {@code priceAsOf} drives {@code stale}. */
    @Test
    void anUnpriceablePortfolioIsCountedAsZeroAndReportedStale() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(portfolio(1L, CurrencyCode.INR)));
        given(valuationService.valuate(eq(USER_ID), eq(1L), any(), eq(CurrencyCode.INR)))
                .willReturn(new ValuationResult(1L, TODAY, CurrencyCode.INR,
                        null, null, null, null, null, null, null, 2, null, null, true));

        OverviewResponse response = service.overview(USER_ID, null);

        assertThat(response.totalValue().amount()).isEqualTo("0.0000");
        assertThat(response.holdingCount()).isEqualTo(2);
        assertThat(response.dataQuality().stale()).isTrue();
    }

    @Test
    void percentageIsNullRatherThanInfiniteWhenCostBasisIsZero() {
        given(portfolioService.findAllByUser(USER_ID)).willReturn(List.of(portfolio(1L, CurrencyCode.INR)));
        givenValuation(1L, CurrencyCode.INR, "1000", "0");

        assertThat(service.overview(USER_ID, null).unrealisedPnlPct()).isNull();
    }

    private static Portfolio portfolio(long id, CurrencyCode currency) {
        return new Portfolio(id, USER_ID, "P" + id, currency, Instant.EPOCH, Instant.EPOCH);
    }

    private void givenValuation(long portfolioId, CurrencyCode currency, String marketValue, String costBasis) {
        BigDecimal market = new BigDecimal(marketValue);
        BigDecimal cost = new BigDecimal(costBasis);
        given(valuationService.valuate(eq(USER_ID), eq(portfolioId), any(), eq(currency)))
                .willReturn(new ValuationResult(
                        portfolioId, TODAY, currency,
                        market, cost, BigDecimal.ZERO, market, market.subtract(cost), BigDecimal.ZERO,
                        null, 1, TODAY, TODAY, false));
    }

    /** Every money field on the subtotal goes through {@code convert} separately, so this stubs
     * the rate rather than a single expected amount. */
    private void givenRate(CurrencyCode from, CurrencyCode to, String rate) {
        given(fxRateService.convert(any(Money.class), eq(to), any(LocalDate.class)))
                .willAnswer(invocation -> {
                    Money money = invocation.getArgument(0);
                    if (money.currency() != from) {
                        return Optional.empty();
                    }
                    return Optional.of(new ConversionResult(
                            money.amount().multiply(new BigDecimal(rate)), to, TODAY));
                });
    }
}
