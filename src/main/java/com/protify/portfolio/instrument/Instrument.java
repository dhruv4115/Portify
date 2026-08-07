package com.protify.portfolio.instrument;

import com.protify.portfolio.common.enums.AssetType;
import com.protify.portfolio.common.enums.CurrencyCode;
import java.time.Instant;

/** Mirrors the {@code instrument} table — the shared catalogue, not user-scoped. */
public record Instrument(
        long id,
        String symbol,
        String name,
        AssetType assetType,
        CurrencyCode currency,
        String exchange,
        String sector,
        Instant createdAt) {
}
