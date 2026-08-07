package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * API_CONTRACT.md §11's fields, computed rather than transported — Dev C's mapper turns
 * {@code priceAsOf}/{@code rateAsOf}/{@code stale} into a {@code DataQualityDto} (day-3-dev-A.md
 * D3-A3). {@code marketValue}, {@code totalValue} and {@code unrealisedPnl} are {@code null}
 * only when the portfolio holds at least one open position and genuinely none of them could be
 * priced — an empty or cash-only portfolio reports zero, never {@code null}, for those fields.
 */
public record ValuationResult(
        long portfolioId,
        LocalDate asOf,
        CurrencyCode currency,
        BigDecimal marketValue,
        BigDecimal costBasis,
        BigDecimal cashBalance,
        BigDecimal totalValue,
        BigDecimal unrealisedPnl,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnlPct,
        int holdingCount,
        LocalDate priceAsOf,
        LocalDate rateAsOf,
        boolean stale) {
}
