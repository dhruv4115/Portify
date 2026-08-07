package com.protify.portfolio.api.dto;

/**
 * API_CONTRACT.md §12's {@code interval}. Lives in {@code api/dto} rather than {@code common}:
 * it is a presentation choice about how densely to sample a series, not a domain concept —
 * nothing is stored or computed per interval, and {@code common} is frozen besides.
 */
public enum PerformanceInterval {
    DAILY, WEEKLY, MONTHLY
}
