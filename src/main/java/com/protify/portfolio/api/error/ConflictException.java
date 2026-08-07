package com.protify.portfolio.api.error;

/**
 * A Dev-C-owned 409, for uniqueness conflicts detected at the controller boundary (typically
 * translated from a persistence-layer {@code DuplicateKeyException}). Deliberately not part
 * of {@code common}'s sealed {@code DomainException} hierarchy — that is frozen and Dev A's;
 * this is scoped to {@code api/} only, for conflicts the API layer discovers rather than the
 * domain rules the service layer enforces.
 */
public class ConflictException extends RuntimeException {

    private final String problemTypeSlug;

    public ConflictException(String problemTypeSlug, String message) {
        super(message);
        this.problemTypeSlug = problemTypeSlug;
    }

    public String problemTypeSlug() {
        return problemTypeSlug;
    }
}
