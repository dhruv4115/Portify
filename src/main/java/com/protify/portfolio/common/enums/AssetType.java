package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code MUTUAL_FUND} and {@code TREASURY} extend REFERENCE_DESIGN §2 per PLAN.md §2.2 — the
 * customer requires a broader asset model. {@code MUTUAL_FUND} is 12 characters, and the
 * column is {@code VARCHAR(16)}, so no DDL change follows from it.
 */
public enum AssetType {
    STOCK, ETF, MUTUAL_FUND, BOND, TREASURY, CASH, CRYPTO;

    private static final Logger log = LoggerFactory.getLogger(AssetType.class);

    public static Optional<AssetType> fromDbValue(String value) {
        return EnumMapping.fromDbValue(AssetType.class, value, log);
    }
}
