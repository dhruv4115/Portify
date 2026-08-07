package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** API_CONTRACT.md §3. */
public record CreatePortfolioRequest(
        @NotBlank @Size(min = 1, max = 120) String name,
        @NotNull CurrencyCode baseCurrency) {
}
