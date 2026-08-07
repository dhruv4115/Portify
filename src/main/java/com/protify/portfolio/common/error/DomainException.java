package com.protify.portfolio.common.error;

import org.springframework.http.HttpStatus;

/**
 * Sealed so {@code GlobalExceptionHandler} can switch over every subclass with no default
 * branch — the compiler enforces exhaustiveness, not a review checklist. Each subclass carries
 * its own {@link #problemType()} slug, matching {@code /docs/API_CONTRACT.md} §0.7 exactly.
 */
public abstract sealed class DomainException extends RuntimeException
        permits NotFoundException, ValidationException, InsufficientQuantityException,
        CurrencyMismatchException, UpstreamException {

    protected DomainException(String message) {
        super(message);
    }

    protected DomainException(String message, Throwable cause) {
        super(message, cause);
    }

    /** e.g. {@code "/errors/insufficient-quantity"} — appended to the problem base URI. */
    public abstract String problemType();

    public abstract HttpStatus status();
}
