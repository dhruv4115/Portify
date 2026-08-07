package com.protify.portfolio.marketdata;

import java.time.LocalDate;

/** One instrument's outcome within a {@link RefreshPricesResult} — API_CONTRACT.md §17. */
public record SymbolRefreshResult(String symbol, RefreshStatus status, int rowsWritten,
                                   LocalDate latestDate, String source) {
}
