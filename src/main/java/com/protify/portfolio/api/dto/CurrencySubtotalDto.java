package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;

/**
 * Everything the user holds in one base currency, summed without any FX applied at all.
 *
 * <p>This is the figure that is true regardless of what any rate says, which is why the
 * overview reports it alongside the converted grand total rather than being replaced by one:
 * a reader who does not accept today's USD/INR rate can still read their rupee total here and
 * it is exactly right.
 */
public record CurrencySubtotalDto(
        CurrencyCode currency,
        int portfolioCount,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto cashBalance,
        MoneyDto totalValue,
        MoneyDto unrealisedPnl,
        String unrealisedPnlPct) {
}
