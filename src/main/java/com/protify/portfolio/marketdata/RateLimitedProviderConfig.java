package com.protify.portfolio.marketdata;

import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import java.time.Clock;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires {@link RateLimitedProvider} around whichever concrete adapter is active
 * ({@code YahooMarketDataProvider} or {@code TwelveDataMarketDataProvider} — exactly one is a
 * candidate at a time, via their own {@code @ConditionalOnProperty}).
 *
 * <p>Why a {@code List<MarketDataProvider>} factory-method parameter rather than making this
 * class itself a {@code @Component MarketDataProvider}: a bean cannot unambiguously
 * constructor-inject "every other bean of my own type" — Spring has no way to exclude itself
 * from a same-typed single-argument autowire. A {@code List} parameter on a {@code @Bean}
 * factory method does not have that problem: Spring resolves it from beans that exist
 * independently of this one, so it contains exactly the one real adapter, never this wrapper.
 *
 * <p>Token bucket sized to the provider's documented tier (ADR-0008, D3-B2): Twelve Data's free
 * tier is 8/min and 800/day; Yahoo's chart endpoint is undocumented, so a conservative 30/min
 * with no daily cap is used instead.
 */
@Configuration
public class RateLimitedProviderConfig {

    private static final int YAHOO_PER_MINUTE = 30;
    private static final int TWELVE_DATA_PER_MINUTE = 8;
    private static final int TWELVE_DATA_PER_DAY = 800;

    @Bean
    @Primary
    public MarketDataProvider rateLimitedMarketDataProvider(List<MarketDataProvider> delegates, Clock clock) {
        MarketDataProvider delegate = delegates.stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No MarketDataProvider adapter bean found to wrap — check marketdata.provider"));

        boolean twelveData = PriceSource.TWELVE_DATA.name().equals(delegate.sourceName());
        int perMinute = twelveData ? TWELVE_DATA_PER_MINUTE : YAHOO_PER_MINUTE;
        int perDay = twelveData ? TWELVE_DATA_PER_DAY : 0;

        return new RateLimitedProvider(delegate, perMinute, perDay, clock);
    }
}
