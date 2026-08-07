package com.protify.portfolio.api.dto;

/**
 * API_CONTRACT.md §12's {@code summary}. {@code netContributions} is deposits minus withdrawals
 * inside the window, so a client can tell "the market moved" apart from "I added money" —
 * without it, a chart that rises because of a deposit looks like a gain.
 *
 * <p>{@code percentChange} is {@code null} when {@code startValue} is zero (TEST_PLAN.md §4.2) —
 * a brand-new portfolio has no baseline to have changed from. Never {@code Infinity}, never
 * {@code 0}, never a 500.
 */
public record PerformanceSummaryDto(
        MoneyDto startValue,
        MoneyDto endValue,
        MoneyDto absoluteChange,
        String percentChange,
        MoneyDto netContributions) {
}
