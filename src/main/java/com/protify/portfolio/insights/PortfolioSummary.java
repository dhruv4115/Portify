package com.protify.portfolio.insights;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;
import java.util.List;

/**
 * The whole request payload to the insights service, and the only thing Dev C's future
 * controller needs to build. Aggregates only — this type has no field for a transaction id,
 * an execution date, or any other ledger-level detail, so "never send transaction-level data
 * to an external LLM" (day-5-dev-B.md) is enforced by the shape, not by a runtime check.
 */
public record PortfolioSummary(CurrencyCode baseCurrency, BigDecimal totalMarketValue,
                                BigDecimal cashBalance, List<HoldingSummary> holdings) {
}
