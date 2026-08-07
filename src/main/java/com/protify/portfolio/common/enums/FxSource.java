package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * New enum per PLAN.md §2.3 — did not exist in REFERENCE_DESIGN §2. Follows from
 * multi-currency portfolios (§2.1) needing a source attribution for FX rates, symmetric with
 * {@link PriceSource} for prices.
 */
public enum FxSource {
    FRANKFURTER, MANUAL, SEED;

    private static final Logger log = LoggerFactory.getLogger(FxSource.class);

    public static Optional<FxSource> fromDbValue(String value) {
        return EnumMapping.fromDbValue(FxSource.class, value, log);
    }
}
