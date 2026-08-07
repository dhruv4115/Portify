package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.LocalDate;
import java.util.List;

/**
 * Everything the user owns, in one response — the aggregation the landing page needs and the
 * one place it can honestly come from.
 *
 * <p><b>Why the server and not the client.</b> Adding rupees to dollars requires a rate for a
 * date. The browser has no rate and no way to get one, so a client-side "net worth" would have
 * to invent it; the server already resolves rates through the dated FX chain that every other
 * money figure in this product goes through, so the same total here is real. That is the whole
 * justification for this endpoint existing rather than the page summing what it already has.
 *
 * <p><b>Both totals are reported, and neither replaces the other.</b> {@link #byCurrency} is
 * arithmetic with no FX in it and is unconditionally true; {@link #totalValue} is that
 * converted into one currency and is only as true as the rate. A reader gets to choose which
 * one to believe, which is not a choice they can make if only one is on the page.
 *
 * @param convertedPortfolioCount how many of {@link #portfolioCount} are actually inside
 *                                {@link #totalValue}. When these differ, the total is a total
 *                                of part of the account, and this is the field that says so —
 *                                it is never silently a smaller number.
 * @param unconvertedCurrencies   the base currencies that had no resolvable rate to
 *                                {@link #displayCurrency} today, and so are excluded from
 *                                {@link #totalValue} but still present in {@link #byCurrency}
 */
public record OverviewResponse(
        CurrencyCode displayCurrency,
        int portfolioCount,
        int holdingCount,
        MoneyDto marketValue,
        MoneyDto costBasis,
        MoneyDto cashBalance,
        MoneyDto totalValue,
        MoneyDto unrealisedPnl,
        String unrealisedPnlPct,
        MoneyDto realisedPnl,
        List<CurrencySubtotalDto> byCurrency,
        int convertedPortfolioCount,
        List<CurrencyCode> unconvertedCurrencies,
        LocalDate asOf,
        DataQualityDto dataQuality) {
}
