package com.protify.portfolio.api.portfolio;

import com.protify.portfolio.api.dto.DataQualityDto;
import java.math.BigDecimal;

/**
 * Everything {@link com.protify.portfolio.api.mapper.PortfolioMapper} needs beyond the raw
 * {@code portfolio} row — the numbers Dev A's valuation engine (not built yet; PLAN.md §3
 * lists {@code valuation/} as Day 3+ work) will eventually own. All amounts are already in the
 * portfolio's base currency.
 */
public record PortfolioSummary(
        int holdingCount,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal cashBalance,
        BigDecimal unrealisedPnl,
        BigDecimal realisedPnl,
        String unrealisedPnlPct,
        DataQualityDto dataQuality) {
}
