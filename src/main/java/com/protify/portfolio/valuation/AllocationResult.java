package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * API_CONTRACT.md §13's fields, computed rather than transported — Dev C's mapper turns
 * {@code priceAsOf}/{@code rateAsOf}/{@code stale} into a {@code DataQualityDto}.
 *
 * <p>{@code total} is the sum of the slices, which is <b>market value only</b>.
 * {@code cashBalance} is reported alongside and is deliberately not part of it: cash has no
 * asset type, no sector and no issuer, so including it would distort every weight while
 * answering no question the chart is asking.
 */
public record AllocationResult(
        long portfolioId,
        AllocateBy by,
        CurrencyCode currency,
        LocalDate asOf,
        BigDecimal total,
        BigDecimal cashBalance,
        List<AllocationSlice> slices,
        LocalDate priceAsOf,
        LocalDate rateAsOf,
        boolean stale) {
}
