package com.protify.portfolio.valuation;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.LocalDate;
import java.util.List;

public record PerformanceResult(
        long portfolioId,
        CurrencyCode currency,
        LocalDate from,
        LocalDate to,
        List<PerformancePoint> points,
        PerformanceSummary summary,
        LocalDate priceAsOf,
        LocalDate rateAsOf,
        boolean stale) {
}
