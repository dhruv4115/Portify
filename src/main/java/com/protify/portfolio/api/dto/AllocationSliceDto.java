package com.protify.portfolio.api.dto;

/** API_CONTRACT.md §13. {@code weightPct} values across one response sum to 100 exactly — the
 * residual from rounding every slice independently is folded into the largest one. */
public record AllocationSliceDto(
        String key,
        String label,
        MoneyDto value,
        String weightPct,
        int instrumentCount) {
}
