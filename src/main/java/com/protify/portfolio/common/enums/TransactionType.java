package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public enum TransactionType {
    BUY, SELL, DIVIDEND, DEPOSIT, WITHDRAWAL, FEE;

    private static final Logger log = LoggerFactory.getLogger(TransactionType.class);

    public static Optional<TransactionType> fromDbValue(String value) {
        return EnumMapping.fromDbValue(TransactionType.class, value, log);
    }
}
