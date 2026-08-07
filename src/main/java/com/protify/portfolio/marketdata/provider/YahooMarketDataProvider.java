package com.protify.portfolio.marketdata.provider;

import com.protify.portfolio.common.enums.PriceSource;
import com.protify.portfolio.common.port.MarketDataProvider;
import com.protify.portfolio.common.port.PriceQuote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * D2-B1 — default {@link MarketDataProvider}: keyless, global coverage, Yahoo's (unofficial,
 * unversioned — ADR-0008) chart endpoint. Selected by {@code marketdata.provider=yahoo}
 * (the default, via {@code @ConditionalOnProperty} below) or when the property is absent.
 *
 * <p>Two rules that are easy to get wrong and expensive to miss, both handled here, before
 * anything is returned to a caller: symbol-to-ticker mapping ({@link YahooSymbolMapping}) and
 * GBX→GBP normalisation for LSE instruments (never stored or returned as pence — R14).
 *
 * <p>Never throws: a timeout, a 429 or a malformed response all result in an empty result, not
 * a propagated exception — {@link com.protify.portfolio.marketdata.CachingMarketDataService}
 * relies on that to fall back to {@code price_history} / seeded data.
 */
@Component
@ConditionalOnProperty(name = "marketdata.provider", havingValue = "yahoo", matchIfMissing = true)
public class YahooMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooMarketDataProvider.class);

    private static final String CHART_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?interval=1d&period1={period1}&period2={period2}";
    private static final int TIMEOUT_MILLIS = 3000;
    private static final int MAX_ATTEMPTS = 2; // one try, one retry

    private final RestClient restClient;

    @Autowired
    public YahooMarketDataProvider() {
        this(defaultRestClient());
    }

    YahooMarketDataProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<PriceQuote> latestPrice(String symbol) {
        List<PriceQuote> recent = dailyCloses(symbol, LocalDate.now(ZoneOffset.UTC).minusDays(10),
                LocalDate.now(ZoneOffset.UTC));
        return recent.stream().max(Comparator.comparing(PriceQuote::date));
    }

    @Override
    public List<PriceQuote> dailyCloses(String symbol, LocalDate from, LocalDate to) {
        String cleanSymbol = symbol.strip().toUpperCase();
        YahooSymbolMapping.Mapping mapping = YahooSymbolMapping.resolve(cleanSymbol);
        long period1 = from.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long period2 = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();

        Optional<YahooChartResponse> response = fetchWithRetry(mapping.providerSymbol(), period1, period2);
        return response.map(r -> toPriceQuotes(r, cleanSymbol, mapping)).orElseGet(List::of);
    }

    @Override
    public String sourceName() {
        return PriceSource.YAHOO.name();
    }

    private Optional<YahooChartResponse> fetchWithRetry(String providerSymbol, long period1, long period2) {
        RestClientException lastError = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                YahooChartResponse body = restClient.get()
                        .uri(CHART_URL, providerSymbol, period1, period2)
                        .retrieve()
                        .body(YahooChartResponse.class);
                return Optional.ofNullable(body);
            } catch (RestClientException e) {
                lastError = e;
                log.warn("Yahoo chart request for {} failed on attempt {}/{}: {}",
                        providerSymbol, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        log.warn("Yahoo chart request for {} exhausted retries; falling back", providerSymbol, lastError);
        return Optional.empty();
    }

    private static List<PriceQuote> toPriceQuotes(YahooChartResponse response, String cleanSymbol,
                                                    YahooSymbolMapping.Mapping mapping) {
        if (response.chart() == null || response.chart().result() == null || response.chart().result().isEmpty()) {
            return List.of();
        }
        YahooChartResponse.YahooResult result = response.chart().result().get(0);
        if (result.timestamp() == null || result.indicators() == null
                || result.indicators().quote() == null || result.indicators().quote().isEmpty()) {
            return List.of();
        }

        List<Long> timestamps = result.timestamp();
        List<BigDecimal> closes = result.indicators().quote().get(0).close();
        List<PriceQuote> quotes = new ArrayList<>();

        for (int i = 0; i < timestamps.size() && i < closes.size(); i++) {
            BigDecimal close = closes.get(i);
            if (close == null) {
                continue; // Yahoo returns null for non-trading days inside the range
            }
            LocalDate date = Instant.ofEpochSecond(timestamps.get(i)).atZone(ZoneOffset.UTC).toLocalDate();
            BigDecimal normalisedClose = mapping.quotedInMinorUnits()
                    ? close.divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                    : close;
            quotes.add(new PriceQuote(cleanSymbol, date, normalisedClose, mapping.currency(), PriceSource.YAHOO));
        }
        return quotes;
    }
}
