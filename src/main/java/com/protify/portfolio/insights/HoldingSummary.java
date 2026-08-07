package com.protify.portfolio.insights;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.math.BigDecimal;

/** One holding's aggregate figures — no symbol-level transaction history, just enough for a
 * concentration/performance/FX read. Never a raw ledger row (D5-B1/D5-B2: aggregates only). */
public record HoldingSummary(String symbol, CurrencyCode currency, BigDecimal marketValue, BigDecimal costBasis) {
}
