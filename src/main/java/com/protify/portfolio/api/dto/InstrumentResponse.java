package com.protify.portfolio.api.dto;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;

/** API_CONTRACT.md §14. Not user-scoped — the instrument catalogue is shared. */
public record InstrumentResponse(
        Long id,
        String symbol,
        String name,
        AssetType assetType,
        CurrencyCode currency,
        String exchange,
        String sector) {
}
