package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code YAHOO} extends REFERENCE_DESIGN §2 per PLAN.md §2.3.
 */
public enum PriceSource {
    YAHOO, TWELVE_DATA, YFINANCE, MANUAL, SEED;

    private static final Logger log = LoggerFactory.getLogger(PriceSource.class);

    public static Optional<PriceSource> fromDbValue(String value) {
        return EnumMapping.fromDbValue(PriceSource.class, value, log);
    }
}
