package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.LocalDate;

/**
 * API_CONTRACT.md §11. Every money field is in {@code currency} — the portfolio's base currency
 * unless {@code ?currency=} overrode it — and {@code totalValue = marketValue + cashBalance}.
 *
 * <p>{@code marketValue}, {@code totalValue} and {@code unrealisedPnl} are {@code null} only when
 * the portfolio holds open positions and none of them could be priced; an empty or cash-only
 * portfolio reports zero for them, never {@code null} and never a 404 (TEST_PLAN.md §4.9).
 */
public record ValuationResponse(
        long portfolioId,
        LocalDate asOf,
        CurrencyCode currency,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto cashBalance,
        MoneyDto totalValue,
        MoneyDto unrealisedPnl,
        MoneyDto realisedPnl,
        String unrealisedPnlPct,
        int holdingCount,
        DataQualityDto dataQuality) {
}
