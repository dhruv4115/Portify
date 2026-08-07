package com.protify.portfolio.marketdata;

/** API_CONTRACT.md §17 — partial success is the normal outcome, not an error. */
public enum RefreshStatus {
    UPDATED, SKIPPED, FAILED
}
