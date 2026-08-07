package com.protify.portfolio.common.error;

import org.springframework.http.HttpStatus;

/**
 * A domain-level 400 — a cross-field or business rule that Bean Validation on the DTO cannot
 * express (e.g. {@code from > to} on a date range). Framework-level Bean Validation failures
 * are a separate {@code MethodArgumentNotValidException} handler in Dev C's
 * {@code GlobalExceptionHandler}; this class is only for rules the service layer checks itself.
 * Defaults to the generic {@code /errors/validation-failed} slug; pass an explicit one
 * (e.g. {@code "invalid-date-range"}) when a more specific slug exists in API_CONTRACT.md §0.7.
 */
public final class ValidationException extends DomainException {

    private static final String DEFAULT_SLUG = "validation-failed";

    private final String slug;

    public ValidationException(String message) {
        this(DEFAULT_SLUG, message);
    }

    public ValidationException(String slug, String message) {
        super(message);
        this.slug = slug;
    }

    @Override
    public String problemType() {
        return "/errors/" + slug;
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_REQUEST;
    }
}
