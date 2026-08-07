package com.protify.portfolio.common.error;

import org.springframework.http.HttpStatus;

/**
 * One class for every "not found", parameterised by resource — {@code /errors/portfolio-not-found},
 * {@code /errors/instrument-not-found}, and so on (API_CONTRACT.md §0.7) — rather than a
 * subclass per resource. "Owned by another user" is also a {@code NotFoundException}: another
 * user's row returns 404, never 403, so existence is never confirmed to the wrong caller.
 */
public final class NotFoundException extends DomainException {

    private final String resource;

    public NotFoundException(String resource, Object identifier) {
        super("%s not found: %s".formatted(resource, identifier));
        this.resource = resource;
    }

    public String resource() {
        return resource;
    }

    @Override
    public String problemType() {
        return "/errors/%s-not-found".formatted(resource);
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
