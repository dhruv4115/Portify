package com.protify.portfolio.api.portfolio;

import com.protify.portfolio.api.dto.DataQualityDto;
import com.protify.portfolio.portfolio.Portfolio;
import com.protify.portfolio.valuation.ValuationResult;
import com.protify.portfolio.valuation.ValuationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/**
 * Real implementation of {@link PortfolioSummaryProvider}, backed by Dev A's
 * {@link ValuationService} (landed Day 3) — replaces the Day 2
 * {@code PlaceholderPortfolioSummaryProvider}, which reported every money field as zero because
 * nothing existed yet to compute a real one. Valuation "as of today, in the portfolio's own
 * base currency" is exactly what the list/create/detail responses need.
 *
 * <p>{@link ValuationResult#marketValue()} (and {@code totalValue}/{@code unrealisedPnl}) can be
 * {@code null} when the portfolio holds at least one open position and genuinely none of them
 * could be priced — that {@code null} is passed straight through rather than defaulted to zero,
 * matching {@code ValuationMapper}'s rule for the same reason: zero would report an unpriced
 * portfolio as worthless.
 */
@Component
class ValuationBackedPortfolioSummaryProvider implements PortfolioSummaryProvider {

    private final ValuationService valuationService;

    ValuationBackedPortfolioSummaryProvider(ValuationService valuationService) {
        this.valuationService = valuationService;
    }

    @Override
    public PortfolioSummary summarize(Portfolio portfolio) {
        ValuationResult result = valuationService.valuate(
                portfolio.userId(), portfolio.id(), LocalDate.now(ZoneOffset.UTC), portfolio.baseCurrency());

        return new PortfolioSummary(
                result.holdingCount(),
                result.marketValue(),
                result.costBasis(),
                result.cashBalance(),
                result.unrealisedPnl(),
                result.realisedPnl(),
                result.unrealisedPnlPct() == null ? null : result.unrealisedPnlPct().toPlainString(),
                new DataQualityDto(result.priceAsOf(), result.rateAsOf(), result.stale()));
    }
}
