package com.protify.portfolio.health;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.fx.FxRateRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * D3-B4 — FX health. Reads {@code fx_rate}'s own freshness rather than calling the provider,
 * for the same reason as {@code MarketDataHealthIndicator}: an actuator probe must not become a
 * hidden request path to a third party. Staleness threshold matches API_CONTRACT.md §0.5's
 * {@code dataQuality.stale} definition (more than 3 calendar days behind "today") — the same
 * definition already used to warn a user in a response is reused here to warn an operator.
 */
@Component("fx")
public class FxHealthIndicator implements HealthIndicator {

    private static final int STALE_THRESHOLD_DAYS = 3;
    private static final CurrencyCode PIVOT = CurrencyCode.USD;
    private static final CurrencyCode SENTINEL = CurrencyCode.EUR;

    private final FxRateRepository fxRateRepository;
    private final Clock clock;

    @Autowired
    public FxHealthIndicator(FxRateRepository fxRateRepository, Clock clock) {
        this.fxRateRepository = fxRateRepository;
        this.clock = clock;
    }

    @Override
    public Health health() {
        LocalDate today = LocalDate.now(clock);
        Optional<LocalDate> latest;
        try {
            latest = fxRateRepository.mostRecentDateOnOrBefore(PIVOT, SENTINEL, today);
        } catch (RuntimeException e) {
            // Genuine DB connectivity failure is already surfaced by Boot's own "db" indicator
            // (which independently drives the aggregate status to DOWN via
            // management.endpoint.health.status.order) — this indicator's job is specifically
            // FX data freshness, so it degrades rather than duplicating "db"'s DOWN signal, and
            // never lets an unhandled exception crash the whole /actuator/health response.
            return Health.status("DEGRADED").withDetail("reason", "unable to read fx_rate freshness").build();
        }

        if (latest.isEmpty()) {
            return Health.status("DEGRADED").withDetail("reason", "no fx rate data available").build();
        }

        long daysStale = ChronoUnit.DAYS.between(latest.get(), today);
        if (daysStale > STALE_THRESHOLD_DAYS) {
            return Health.status("DEGRADED")
                    .withDetail("rateAsOf", latest.get())
                    .withDetail("daysStale", daysStale)
                    .build();
        }
        return Health.up().withDetail("rateAsOf", latest.get()).build();
    }
}
