package com.protify.portfolio.marketdata;

import java.util.List;

/** API_CONTRACT.md §17 — {@code POST /admin/prices/refresh}'s response shape. */
public record RefreshPricesResult(int requested, int updated, int skipped, int failed,
                                   List<SymbolRefreshResult> results) {
}
