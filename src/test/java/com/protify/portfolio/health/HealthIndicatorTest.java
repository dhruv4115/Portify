package com.protify.portfolio.health;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.fx.FxRateRepository;
import com.protify.portfolio.marketdata.RateLimitedProvider;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * D3-B4 — {@code HealthIndicatorTest}: all providers throwing → overall stays healthy
 * ({@code UP}-shaped, never {@code DOWN}), with the affected component reporting
 * {@code DEGRADED}. A stale price is not an outage.
 */
@ExtendWith(MockitoExtension.class)
class HealthIndicatorTest {

    @Mock
    private MarketDataProvider rawDelegate;
    @Mock
    private FxRateRepository fxRateRepository;

    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void marketDataIsDegradedWhenTheProviderIsThrowingAndTheCircuitIsOpen() {
        when(rawDelegate.sourceName()).thenReturn("YAHOO");
        when(rawDelegate.dailyCloses("AAPL", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5)))
                .thenThrow(new RuntimeException("network is off"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
        RateLimitedProvider rateLimited = new RateLimitedProvider(rawDelegate, 30, 0, clock);

        // Drive 3 consecutive failures so the circuit opens, exactly like a real network outage.
        rateLimited.dailyCloses("AAPL", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        clock.advance(Duration.ofSeconds(10));
        rateLimited.dailyCloses("AAPL", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        clock.advance(Duration.ofSeconds(10));
        rateLimited.dailyCloses("AAPL", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 5));
        assertThat(rateLimited.isCircuitOpen()).isTrue();

        Health health = new MarketDataHealthIndicator(rateLimited).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
    }

    @Test
    void marketDataIsUpWhenTheCircuitIsClosed() {
        when(rawDelegate.sourceName()).thenReturn("YAHOO");
        RateLimitedProvider rateLimited = new RateLimitedProvider(rawDelegate, 30, 0, Clock.systemUTC());

        Health health = new MarketDataHealthIndicator(rateLimited).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void fxIsDegradedWhenTheMostRecentRateIsMoreThanThreeDaysStale() {
        LocalDate today = LocalDate.of(2026, 1, 10);
        LocalDate staleDate = today.minusDays(5);
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        when(fxRateRepository.mostRecentDateOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, today))
                .thenReturn(Optional.of(staleDate));

        Health health = new FxHealthIndicator(fxRateRepository, clock).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("rateAsOf", staleDate);
    }

    @Test
    void fxIsUpWhenTheMostRecentRateIsFresh() {
        LocalDate today = LocalDate.of(2026, 1, 10);
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        when(fxRateRepository.mostRecentDateOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, today))
                .thenReturn(Optional.of(today.minusDays(1)));

        Health health = new FxHealthIndicator(fxRateRepository, clock).health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void fxIsDegradedWhenThereIsNoDataAtAll() {
        LocalDate today = LocalDate.of(2026, 1, 10);
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        when(fxRateRepository.mostRecentDateOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, today))
                .thenReturn(Optional.empty());

        Health health = new FxHealthIndicator(fxRateRepository, clock).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
    }

    @Test
    void fxDegradesRatherThanCrashingWhenTheRepositoryThrows() {
        LocalDate today = LocalDate.of(2026, 1, 10);
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);
        when(fxRateRepository.mostRecentDateOnOrBefore(CurrencyCode.USD, CurrencyCode.EUR, today))
                .thenThrow(new RuntimeException("connection refused"));

        Health health = new FxHealthIndicator(fxRateRepository, clock).health();

        assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
        assertThat(health.getStatus()).isNotEqualTo(Status.DOWN);
    }
}
