package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * D3-B2 — a decorator around any {@link MarketDataProvider}: token bucket (per-minute, plus an
 * optional per-day cap), exponential backoff with jitter after a failure, and a 3-failure /
 * 5-minute circuit breaker. Hand-rolled per ADR-0008 ("Resilience4j would be better with a
 * spare half-day... do not add the dependency today").
 *
 * <p><b>Never calls the delegate</b> while the bucket is empty, while in a post-failure backoff
 * window, or while the circuit is open — and never lets an exception from the delegate escape.
 * A miss for any of those reasons returns the same "nothing available" value the interface
 * already uses for a real empty result ({@link Optional#empty()} / an empty {@link List}), so
 * {@link CachingMarketDataService} falls through to {@code price_history} exactly as it does
 * for any other empty result.
 *
 * <p>Not a {@code @Component} itself — see {@link RateLimitedProviderConfig} for why letting
 * Spring autowire the wrapped delegate directly (rather than via a bean-collection factory
 * method) would create ambiguity between this class and the adapter it wraps.
 */
public class RateLimitedProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(RateLimitedProvider.class);

    private static final Duration OPEN_DURATION = Duration.ofMinutes(5);
    private static final int FAILURE_THRESHOLD = 3;
    private static final Duration BASE_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

    private enum BreakerState { CLOSED, OPEN, HALF_OPEN }

    private final MarketDataProvider delegate;
    private final Clock clock;
    private final int capacityPerMinute;
    private final int capacityPerDay; // <= 0 means "no documented daily cap" (Yahoo)

    private double minuteTokens;
    private Instant minuteRefillAt;
    private double dayTokens;
    private Instant dayRefillAt;

    private int consecutiveFailures = 0;
    private BreakerState state = BreakerState.CLOSED;
    private Instant openedAt;
    private Instant backoffUntil;

    public RateLimitedProvider(MarketDataProvider delegate, int capacityPerMinute, int capacityPerDay, Clock clock) {
        this.delegate = delegate;
        this.capacityPerMinute = capacityPerMinute;
        this.capacityPerDay = capacityPerDay;
        this.clock = clock;
        Instant now = clock.instant();
        this.minuteTokens = capacityPerMinute;
        this.minuteRefillAt = now;
        this.dayTokens = capacityPerDay;
        this.dayRefillAt = now;
    }

    @Override
    public synchronized Optional<PriceQuote> latestPrice(String symbol) {
        return guarded(() -> delegate.latestPrice(symbol), Optional.empty(), "latestPrice(" + symbol + ")");
    }

    @Override
    public synchronized List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        return guarded(() -> delegate.dailyCloses(symbol, from, to), List.of(), "dailyCloses(" + symbol + ")");
    }

    @Override
    public String sourceName() {
        return delegate.sourceName();
    }

    /** Read-only, for {@code health/MarketDataHealthIndicator} — a stale price is not an
     * outage, so health reporting inspects breaker state rather than calling the provider. */
    public synchronized boolean isCircuitOpen() {
        return state == BreakerState.OPEN && clock.instant().isBefore(openedAt.plus(OPEN_DURATION));
    }

    private <T> T guarded(Supplier<T> call, T emptyValue, String opName) {
        Instant now = clock.instant();

        if (state == BreakerState.OPEN) {
            if (now.isBefore(openedAt.plus(OPEN_DURATION))) {
                log.warn("Circuit open for {}; skipping call to {}", delegate.sourceName(), opName);
                return emptyValue;
            }
            state = BreakerState.HALF_OPEN;
            log.info("Circuit half-open for {}; allowing a trial call to {}", delegate.sourceName(), opName);
        }

        if (backoffUntil != null && now.isBefore(backoffUntil)) {
            log.warn("Backing off {} until {}; skipping call to {}", delegate.sourceName(), backoffUntil, opName);
            return emptyValue;
        }

        if (!tryConsumeToken(now)) {
            log.warn("Token bucket exhausted for {}; skipping call to {}", delegate.sourceName(), opName);
            return emptyValue;
        }

        try {
            T result = call.get();
            onSuccess();
            return result;
        } catch (RuntimeException e) {
            onFailure(now);
            log.warn("{} failed for {}: {}", opName, delegate.sourceName(), e.getMessage());
            return emptyValue;
        }
    }

    private boolean tryConsumeToken(Instant now) {
        refill(now);
        if (capacityPerDay > 0 && dayTokens < 1.0) {
            return false;
        }
        if (minuteTokens < 1.0) {
            return false;
        }
        minuteTokens -= 1.0;
        if (capacityPerDay > 0) {
            dayTokens -= 1.0;
        }
        return true;
    }

    private void refill(Instant now) {
        Duration sinceMinuteRefill = Duration.between(minuteRefillAt, now);
        if (sinceMinuteRefill.toMillis() > 0) {
            double toAdd = sinceMinuteRefill.toMillis() / 60_000.0 * capacityPerMinute;
            if (toAdd > 0) {
                minuteTokens = Math.min(capacityPerMinute, minuteTokens + toAdd);
                minuteRefillAt = now;
            }
        }
        if (capacityPerDay > 0) {
            Duration sinceDayRefill = Duration.between(dayRefillAt, now);
            if (sinceDayRefill.toMillis() > 0) {
                double toAdd = sinceDayRefill.toMillis() / 86_400_000.0 * capacityPerDay;
                if (toAdd > 0) {
                    dayTokens = Math.min(capacityPerDay, dayTokens + toAdd);
                    dayRefillAt = now;
                }
            }
        }
    }

    private void onSuccess() {
        consecutiveFailures = 0;
        backoffUntil = null;
        if (state == BreakerState.HALF_OPEN) {
            log.info("Circuit closed for {} after a successful trial call", delegate.sourceName());
        }
        state = BreakerState.CLOSED;
    }

    private void onFailure(Instant now) {
        consecutiveFailures++;
        long backoffMillis = Math.min(MAX_BACKOFF.toMillis(),
                BASE_BACKOFF.toMillis() * (1L << Math.min(consecutiveFailures - 1, 10)));
        long jitterMillis = ThreadLocalRandom.current().nextLong(0, backoffMillis / 2 + 1);
        backoffUntil = now.plusMillis(backoffMillis + jitterMillis);

        if (state == BreakerState.HALF_OPEN) {
            state = BreakerState.OPEN;
            openedAt = now;
            log.warn("Trial call failed; circuit re-opened for {} until {}", delegate.sourceName(), openedAt.plus(OPEN_DURATION));
        } else if (consecutiveFailures >= FAILURE_THRESHOLD) {
            state = BreakerState.OPEN;
            openedAt = now;
            log.warn("{} consecutive failures; circuit opened for {} until {}", consecutiveFailures,
                    delegate.sourceName(), openedAt.plus(OPEN_DURATION));
        }
    }
}
