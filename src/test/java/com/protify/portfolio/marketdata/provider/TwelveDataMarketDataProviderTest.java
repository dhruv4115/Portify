package com.protify.portfolio.marketdata.provider;

import com.protify.portfolio.common.port.PriceQuote;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2-B1 — {@code TwelveDataMarketDataProviderTest}: disabled when no key is configured. No test
 * here calls the real Twelve Data endpoint.
 */
class TwelveDataMarketDataProviderTest {

    @Test
    void isDisabledWhenApiKeyIsAbsent() {
        TwelveDataMarketDataProvider provider = new TwelveDataMarketDataProvider("", RestClient.builder().build());

        assertThat(provider.latestPrice("AAPL")).isEmpty();
        assertThat(provider.dailyCloses("AAPL", LocalDate.now().minusDays(5), LocalDate.now())).isEmpty();
    }

    @Test
    void isDisabledWhenApiKeyIsBlank() {
        TwelveDataMarketDataProvider provider = new TwelveDataMarketDataProvider("   ", RestClient.builder().build());

        assertThat(provider.latestPrice("AAPL")).isEmpty();
    }

    @Test
    void sourceNameIsTwelveDataRegardlessOfEnablement() {
        TwelveDataMarketDataProvider provider = new TwelveDataMarketDataProvider("", RestClient.builder().build());

        assertThat(provider.sourceName()).isEqualTo("TWELVE_DATA");
    }

    @Test
    void neverCallsHttpWhenDisabled() {
        // A RestClient pointed at an address that always refuses connections — if the provider
        // ever tried to call it despite being disabled, this would throw, not return empty.
        RestClient unreachable = RestClient.builder().baseUrl("http://127.0.0.1:1").build();
        TwelveDataMarketDataProvider provider = new TwelveDataMarketDataProvider("", unreachable);

        Optional<PriceQuote> result = provider.latestPrice("AAPL");
        List<PriceQuote> closes = provider.dailyCloses("AAPL", LocalDate.now().minusDays(1), LocalDate.now());

        assertThat(result).isEmpty();
        assertThat(closes).isEmpty();
    }
}
