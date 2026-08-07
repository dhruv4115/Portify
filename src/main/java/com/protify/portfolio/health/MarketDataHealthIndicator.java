package com.protify.portfolio.health;

import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.marketdata.RateLimitedProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * D3-B4 — market data health. Deliberately does <b>not</b> call the provider: an actuator
 * health probe is itself a request path, and calling a rate-limited provider from it would be
 * exactly the kind of request-triggered call ADR-0008/RISKS.md R4 exist to prevent. Instead it
 * reads {@link RateLimitedProvider}'s own circuit-breaker state, which the provider's real
 * failures already drive (D3-B2).
 *
 * <p>A stale price is not an outage (D3-B4): an open circuit means the last few calls failed,
 * not that the product is broken — {@code price_history} still serves reads. So this reports
 * {@code DEGRADED}, never {@code DOWN}; {@code management.endpoint.health.status.order} in
 * application.properties makes sure a {@code DEGRADED} component does not drag the aggregate
 * {@code /actuator/health} status down from {@code UP}.
 */
@Component("marketdata")
public class MarketDataHealthIndicator implements HealthIndicator {

    private final MarketDataProvider marketDataProvider;

    @Autowired
    public MarketDataHealthIndicator(MarketDataProvider marketDataProvider) {
        this.marketDataProvider = marketDataProvider;
    }

    @Override
    public Health health() {
        if (marketDataProvider instanceof RateLimitedProvider rateLimited && rateLimited.isCircuitOpen()) {
            return Health.status("DEGRADED")
                    .withDetail("reason", "circuit breaker open — serving cached / last-good prices")
                    .withDetail("source", marketDataProvider.sourceName())
                    .build();
        }
        return Health.up().withDetail("source", marketDataProvider.sourceName()).build();
    }
}
