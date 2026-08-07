package com.protify.portfolio.common.enums;

import java.util.Optional;
import org.slf4j.Logger;

/**
 * Shared body for every enum's {@code fromDbValue}: never throw, always log the value that
 * did not match. {@code valueOf} inside a {@code RowMapper} would blow up four frames deep in
 * JDBC on an unrecognised value — this is the one place that decision is made instead.
 */
final class EnumMapping {

    private EnumMapping() {
    }

    static <T extends Enum<T>> Optional<T> fromDbValue(Class<T> type, String value, Logger log) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Enum.valueOf(type, value));
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised {} value from database: '{}'", type.getSimpleName(), value);
            return Optional.empty();
        }
    }
}
