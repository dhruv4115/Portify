package com.protify.portfolio.common.error;

import org.springframework.http.HttpStatus;

/**
 * The last resort when a market-data or FX provider fails <em>and</em> every fallback in the
 * chain — cache, {@code price_history}/{@code fx_rate} last-good row, seeded data — is also
 * exhausted (ADR-0008). Should be almost unreachable by design; reserved for a cold cache on an
 * instrument with no seed data.
 */
public final class UpstreamException extends DomainException {

    private final String provider;

    public UpstreamException(String provider, String message) {
        super(message);
        this.provider = provider;
    }

    public UpstreamException(String provider, String message, Throwable cause) {
        super(message, cause);
        this.provider = provider;
    }

    public String provider() {
        return provider;
    }

    @Override
    public String problemType() {
        return "/errors/upstream-unavailable";
    }

    @Override
    public HttpStatus status() {
        return HttpStatus.BAD_GATEWAY;
    }
}
