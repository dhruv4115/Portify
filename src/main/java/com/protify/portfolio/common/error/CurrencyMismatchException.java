package com.protify.portfolio.common.error;

import com.protify.portfolio.common.enums.CurrencyCode;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a currency that must match does not — e.g. a transaction's {@code currency}
 * against its instrument's native currency. Carries both sides so the message is specific.
 */
public final class CurrencyMismatchException extends DomainException {

    private final CurrencyCode expected;
    private final CurrencyCode actual;

    public CurrencyMismatchException(CurrencyCode expected, CurrencyCode actual) {
        super("Currency mismatch: expected %s but got %s.".formatted(expected, actual));
        this.expected = expected;
        this.actual = actual;
    }

    public CurrencyCode expected() {
        return expected;
    }

    public CurrencyCode actual() {
        return actual;
    }

    @Override
    public String problemType() {
        return "/errors/currency-mismatch";
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.UNPROCESSABLE_ENTITY;
    }
}
