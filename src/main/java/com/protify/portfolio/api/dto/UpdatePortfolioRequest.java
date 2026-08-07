package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import jakarta.validation.constraints.Size;

/**
 * API_CONTRACT.md §5. Both fields optional; the controller rejects the case where neither is
 * present (400) since that isn't expressible as a single-field Bean Validation constraint.
 */
public record UpdatePortfolioRequest(
        @Size(min = 1, max = 120) String name,
        CurrencyCode baseCurrency) {
}
