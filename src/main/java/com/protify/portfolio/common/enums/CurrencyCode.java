package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum CurrencyCode {
    USD, EUR, GBP, INR;

    private static final Logger log = LoggerFactory.getLogger(CurrencyCode.class);

    public static Optional<CurrencyCode> fromDbValue(String value) {
        return EnumMapping.fromDbValue(CurrencyCode.class, value, log);
    }
}
