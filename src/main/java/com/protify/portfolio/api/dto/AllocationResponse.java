package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.CurrencyCode;
import java.util.List;

/** API_CONTRACT.md §13. Cash is excluded — {@code total} is the sum of holding market values
 * only, never {@code cashBalance}. */
public record AllocationResponse(
        long portfolioId,
        AllocationDimension by,
        CurrencyCode currency,
        MoneyDto total,
        List<AllocationSliceDto> slices,
        DataQualityDto dataQuality) {
}
