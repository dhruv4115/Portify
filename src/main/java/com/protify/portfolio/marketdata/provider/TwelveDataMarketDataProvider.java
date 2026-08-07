package com.protify.portfolio.marketdata.provider;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * D2-B1 — alternative {@link MarketDataProvider}, selected by
 * {@code MARKET_DATA_PROVIDER=twelve_data}. **Auto-disables itself when
 * {@code TWELVE_DATA_API_KEY} is absent** rather than failing at startup — {@link #enabled}
 * short-circuits every method to an empty result with no HTTP call, so leaving the key unset
 * (the safe local default) never breaks the app; it just means this adapter never returns data
 * and {@code CachingMarketDataService} falls through to the next link in the chain.
 */
@Component
@ConditionalOnProperty(name = "marketdata.provider", havingValue = "twelve_data")
public class TwelveDataMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(TwelveDataMarketDataProvider.class);

    private static final String TIME_SERIES_URL = "https://api.twelvedata.com/time_series"
            + "?symbol={symbol}&interval=1day&start_date={start}&end_date={end}&apikey={apikey}";
    private static final int TIMEOUT_MILLIS = 3000;
    private static final int MAX_ATTEMPTS = 2;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final RestClient restClient;
    private final String apiKey;
    private final boolean enabled;

    @Autowired
    public TwelveDataMarketDataProvider(@Value("${marketdata.twelvedata.api-key:}") String apiKey) {
        this(apiKey, defaultRestClient());
    }

    TwelveDataMarketDataProvider(String apiKey, RestClient restClient) {
        this.apiKey = apiKey;
        this.enabled = apiKey != null && !apiKey.isBlank();
        this.restClient = restClient;
        if (!enabled) {
            log.info("TwelveDataMarketDataProvider disabled: TWELVE_DATA_API_KEY is not set");
        }
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<PriceQuote> latestPrice(String symbol) {
        if (!enabled) {
            return Optional.empty();
        }
        List<PriceQuote> recent = dailyCloses(symbol, LocalDate.now().minusDays(10), LocalDate.now());
        return recent.stream().max(Comparator.comparing(PriceQuote::date));
    }

    @Override
    public List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        if (!enabled) {
            return List.of();
        }
        String cleanSymbol = symbol.strip().toUpperCase();

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                TwelveDataTimeSeriesResponse body = restClient.get()
                        .uri(TIME_SERIES_URL, cleanSymbol, DATE_FORMAT.format(from), DATE_FORMAT.format(to), apiKey)
                        .retrieve()
                        .body(TwelveDataTimeSeriesResponse.class);
                return toPriceQuotes(body, cleanSymbol);
            } catch (RestClientException e) {
                log.warn("Twelve Data request for {} failed on attempt {}/{}: {}",
                        cleanSymbol, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return List.of();
    }

    @Override
    public String sourceName() {
        return PriceSource.TWELVE_DATA.name();
    }

    private static List<PriceQuote> toPriceQuotes(TwelveDataTimeSeriesResponse body, String symbol) {
        if (body == null || body.values() == null || !"ok".equalsIgnoreCase(body.status())) {
            return List.of();
        }
        return body.values().stream()
                .map(value -> new PriceQuote(symbol, LocalDate.parse(value.datetime()),
                        new BigDecimal(value.close()), CurrencyCode.USD, PriceSource.TWELVE_DATA))
                .toList();
    }

    private record TwelveDataTimeSeriesResponse(String status, List<Value> values) {
        private record Value(String datetime, String close) {
        }
    }
}
