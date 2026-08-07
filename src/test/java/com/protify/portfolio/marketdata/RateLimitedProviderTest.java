package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D3-B2 — {@code RateLimitedProviderTest}: every collaborator is a mock or a controllable fake
 * clock — no {@code Thread.sleep}, no real provider, no real time elapsing.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitedProviderTest {

    @Mock
    private MarketDataProvider delegate;

    private static final LocalDate DAY = LocalDate.of(2026, 1, 5);
    private static final List<PriceQuote> SOME_QUOTES =
            List.of(new PriceQuote("AAPL", DAY, new BigDecimal("190.0000"), CurrencyCode.USD, PriceSource.YAHOO));

    /** A {@link Clock} whose {@code instant()} can be advanced explicitly — the test drives
     * time forward itself instead of waiting for it. */
    private static final class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now = now; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    @Test
    void fiftyRapidRequestsNeverExceedTheTokenBucket() {
        when(delegate.sourceName()).thenReturn("YAHOO");
        when(delegate.dailyCloses("AAPL", DAY, DAY)).thenReturn(SOME_QUOTES);
        MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
        RateLimitedProvider provider = new RateLimitedProvider(delegate, 5, 0, clock);

        for (int i = 0; i < 50; i++) {
            provider.dailyCloses("AAPL", DAY, DAY);
        }

        verify(delegate, times(5)).dailyCloses("AAPL", DAY, DAY);
    }

    @Test
    void aSimulated429TriggersBackoffAndNoExceptionEscapes() {
        when(delegate.sourceName()).thenReturn("YAHOO");
        when(delegate.dailyCloses("AAPL", DAY, DAY)).thenThrow(new RuntimeException("429 Too Many Requests"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
        RateLimitedProvider provider = new RateLimitedProvider(delegate, 30, 0, clock);

        List<PriceQuote> first = provider.dailyCloses("AAPL", DAY, DAY);
        List<PriceQuote> second = provider.dailyCloses("AAPL", DAY, DAY); // immediately after, still backing off

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        verify(delegate, times(1)).dailyCloses("AAPL", DAY, DAY); // second call was blocked by backoff, not retried
    }

    @Test
    void threeConsecutiveFailuresOpenTheCircuitAndStopCallingTheProvider() {
        when(delegate.sourceName()).thenReturn("YAHOO");
        when(delegate.dailyCloses("AAPL", DAY, DAY)).thenThrow(new RuntimeException("upstream down"));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
        RateLimitedProvider provider = new RateLimitedProvider(delegate, 30, 0, clock);

        // 10s between attempts clears each failure's short backoff without approaching the
        // 5-minute circuit-open window, so each of these genuinely reaches the delegate.
        provider.dailyCloses("AAPL", DAY, DAY);
        clock.advance(Duration.ofSeconds(10));
        provider.dailyCloses("AAPL", DAY, DAY);
        clock.advance(Duration.ofSeconds(10));
        provider.dailyCloses("AAPL", DAY, DAY);

        assertThat(provider.isCircuitOpen()).isTrue();
        verify(delegate, times(3)).dailyCloses("AAPL", DAY, DAY);

        // A 4th call, still within the open window: the provider must not be called at all.
        List<PriceQuote> fourth = provider.dailyCloses("AAPL", DAY, DAY);
        assertThat(fourth).isEmpty();
        verify(delegate, times(3)).dailyCloses("AAPL", DAY, DAY); // still 3 — the 4th never reached the delegate
    }

    @Test
    void itHalfOpensAfterFiveMinutesAndClosesOnASuccessfulTrial() {
        when(delegate.sourceName()).thenReturn("YAHOO");
        MutableClock clock = new MutableClock(Instant.parse("2026-01-05T09:00:00Z"));
        RateLimitedProvider provider = new RateLimitedProvider(delegate, 30, 0, clock);

        when(delegate.dailyCloses("AAPL", DAY, DAY)).thenThrow(new RuntimeException("down"));
        provider.dailyCloses("AAPL", DAY, DAY);
        clock.advance(Duration.ofSeconds(10));
        provider.dailyCloses("AAPL", DAY, DAY);
        clock.advance(Duration.ofSeconds(10));
        provider.dailyCloses("AAPL", DAY, DAY);
        assertThat(provider.isCircuitOpen()).isTrue();

        clock.advance(Duration.ofMinutes(5).plusSeconds(1));
        // doReturn(...).when(...), not when(...).thenReturn(...): the mock is currently
        // stubbed to throw, and when(mock.method()) would invoke it for real to register the
        // call, triggering that old throw before the new stub is even set.
        org.mockito.Mockito.doReturn(SOME_QUOTES).when(delegate).dailyCloses("AAPL", DAY, DAY); // recovered

        List<PriceQuote> trial = provider.dailyCloses("AAPL", DAY, DAY);

        assertThat(trial).isEqualTo(SOME_QUOTES);
        assertThat(provider.isCircuitOpen()).isFalse();
        verify(delegate, times(4)).dailyCloses("AAPL", DAY, DAY); // 3 failures + the successful trial
    }
}
