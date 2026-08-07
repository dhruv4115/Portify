package com.protify.portfolio.common.port;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Implemented by adapters in {@code portfolio-platform} (ADR-0008), same boundary rationale as
 * {@link MarketDataProvider}. Required by PLAN.md §2.1 — multi-currency portfolios need FX at
 * each transaction's date, and it must land on Day 1 so {@code common} can freeze on time.
 */
public interface FxRateProvider {

    Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate on);

    Map<CurrencyCode, BigDecimal> ratesFor(CurrencyCode base, LocalDate on);

    String sourceName();
}
