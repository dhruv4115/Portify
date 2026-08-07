package com.protify.portfolio.api.error;

/**
 * A Dev-C-owned 501, for an endpoint that exists and is routed but whose feature flag is off
 * (API_CONTRACT.md §18). Deliberately not part of {@code common}'s sealed {@code DomainException}
 * hierarchy — same reasoning as {@link ConflictException}: that hierarchy is frozen and Dev A's,
 * and this is not a domain rule at all. Nothing about the request was wrong.
 *
 * <p>501 rather than 404 is the entire point. A 404 says "no such URL", which sends a client
 * looking for a typo in a path that is in fact correct; 501 says "this URL is real and turned
 * off", which is both true and actionable.
 */
public class FeatureDisabledException extends RuntimeException {

    private final String problemTypeSlug;

    public FeatureDisabledException(String problemTypeSlug, String message) {
        super(message);
        this.problemTypeSlug = problemTypeSlug;
    }

    public String problemTypeSlug() {
        return problemTypeSlug;
    }
}
