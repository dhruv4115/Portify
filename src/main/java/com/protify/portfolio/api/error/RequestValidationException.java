package com.protify.portfolio.api.error;

import java.util.List;

/**
 * A Dev-C-owned 400 that names the query parameters at fault. Same reasoning as
 * {@link ConflictException} for living outside {@code common}'s sealed {@code DomainException}
 * hierarchy — that is frozen and Dev A's.
 *
 * <p>It exists because {@code common.ValidationException} carries a message and a slug but no
 * field list, so a rule it enforces reaches the client as a 400 with an empty {@code errors[]}.
 * API_CONTRACT.md §12 and day-3-dev-C.md D3-C3 both require {@code from > to} to name a field,
 * and the add/filter forms bind their inline errors to exactly that array.
 */
public final class RequestValidationException extends RuntimeException {

    private final String problemTypeSlug;
    private final List<FieldViolation> violations;

    public RequestValidationException(String problemTypeSlug, String message, List<FieldViolation> violations) {
        super(message);
        this.problemTypeSlug = problemTypeSlug;
        this.violations = List.copyOf(violations);
    }

    public String problemTypeSlug() {
        return problemTypeSlug;
    }

    public List<FieldViolation> violations() {
        return violations;
    }
}
