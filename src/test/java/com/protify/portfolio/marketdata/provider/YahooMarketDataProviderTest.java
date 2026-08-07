package com.protify.portfolio.marketdata.provider;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.PriceQuote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * D2-B1 — {@code YahooMarketDataProviderTest}: {@link MockRestServiceServer} against a captured
 * response shape. No test here calls the real Yahoo endpoint.
 */
class YahooMarketDataProviderTest {

    // A trimmed but realistic capture of Yahoo's /v8/finance/chart/{symbol} shape.
    private static final String AAPL_RESPONSE = """
            {
              "chart": {
                "result": [{
                  "meta": { "currency": "USD", "symbol": "AAPL" },
                  "timestamp": [1704153600, 1704240000, 1704326400],
                  "indicators": {
                    "quote": [{ "close": [185.64, 181.91, 186.19] }]
                  }
                }],
                "error": null
              }
            }
            """;

    private static final String SHEL_RESPONSE_IN_PENCE = """
            {
              "chart": {
                "result": [{
                  "meta": { "currency": "GBP", "symbol": "SHEL.L" },
                  "timestamp": [1704153600],
                  "indicators": {
                    "quote": [{ "close": [2915.0] }]
                  }
                }],
                "error": null
              }
            }
            """;

    private static final String EMPTY_RESULT_RESPONSE = """
            { "chart": { "result": [], "error": null } }
            """;

    private MockRestServiceServer server;
    private YahooMarketDataProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new YahooMarketDataProvider(builder.build());
    }

    @Test
    void shouldParseARealCapturedResponse() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("AAPL")))
                .andRespond(withSuccess(AAPL_RESPONSE, MediaType.APPLICATION_JSON));

        List<PriceQuote> quotes = provider.dailyCloses("AAPL", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 4));

        assertThat(quotes).hasSize(3);
        assertThat(quotes.get(0).symbol()).isEqualTo("AAPL");
        assertThat(quotes.get(0).currency()).isEqualTo(CurrencyCode.USD);
        assertThat(quotes.get(0).source()).isEqualTo(PriceSource.YAHOO);
        assertThat(quotes.get(0).close()).isEqualByComparingTo("185.6400");
    }

    @Test
    void shouldConvertGbxToGbpOnIngestion() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("SHEL.L")))
                .andRespond(withSuccess(SHEL_RESPONSE_IN_PENCE, MediaType.APPLICATION_JSON));

        List<PriceQuote> quotes = provider.dailyCloses("SHEL", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2));

        assertThat(quotes).hasSize(1);
        assertThat(quotes.get(0).currency()).isEqualTo(CurrencyCode.GBP);
        // 2915 pence -> GBP 29.15, never stored/returned as pence (R14)
        assertThat(quotes.get(0).close()).isEqualByComparingTo("29.1500");
    }

    @Test
    void aRateLimitResponseDoesNotThrowAndReturnsEmpty() {
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.containsString("AAPL")))
                .andRespond(withStatus(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS));

        assertThatCode(() -> {
            List<PriceQuote> quotes = provider.dailyCloses("AAPL", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2));
            assertThat(quotes).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void aTimeoutDoesNotThrow() {
        ResponseCreator simulatedTimeout = request -> {
            throw new IOException("simulated read timeout");
        };
        server.expect(org.springframework.test.web.client.ExpectedCount.times(2), requestTo(org.hamcrest.Matchers.containsString("AAPL")))
                .andRespond(simulatedTimeout);

        assertThatCode(() -> {
            List<PriceQuote> quotes = provider.dailyCloses("AAPL", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2));
            assertThat(quotes).isEmpty();
        }).doesNotThrowAnyException();
    }

    @Test
    void emptyResultArrayReturnsEmptyList() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("MSFT")))
                .andRespond(withSuccess(EMPTY_RESULT_RESPONSE, MediaType.APPLICATION_JSON));

        List<PriceQuote> quotes = provider.dailyCloses("MSFT", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 2));

        assertThat(quotes).isEmpty();
    }

    @Test
    void sourceNameIsYahoo() {
        assertThat(provider.sourceName()).isEqualTo("YAHOO");
    }

    @Test
    void latestPriceReturnsTheMostRecentQuote() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("AAPL")))
                .andRespond(withSuccess(AAPL_RESPONSE, MediaType.APPLICATION_JSON));

        Optional<PriceQuote> latest = provider.latestPrice("AAPL");

        assertThat(latest).isPresent();
        assertThat(latest.get().date()).isEqualTo(LocalDate.of(2024, 1, 4));
    }
}
