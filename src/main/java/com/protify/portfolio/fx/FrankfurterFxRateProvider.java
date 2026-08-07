package com.protify.portfolio.fx;

import com.protify.portfolio.common.enums.CurrencyCode;
import com.protify.portfolio.common.enums.FxSource;
import com.protify.portfolio.common.port.FxQuote;
import com.protify.portfolio.common.port.FxRateProvider;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * D2-B3 — keyless FX provider, ECB reference rates via Frankfurter, historical endpoint
 * included. Selected by {@code fx.provider=frankfurter} (the default).
 *
 * <p>Never throws: a timeout or malformed response yields an empty result, exactly like the
 * market-data adapters — {@link CachingFxRateService} relies on that to fall back to
 * {@code fx_rate}'s last-good row / seeded data.
 */
@Component
@ConditionalOnProperty(name = "fx.provider", havingValue = "frankfurter", matchIfMissing = true)
public class FrankfurterFxRateProvider implements FxRateProvider {

    private static final Logger log = LoggerFactory.getLogger(FrankfurterFxRateProvider.class);

    private static final String RATE_URL = "https://api.frankfurter.app/{date}?from={from}&to={to}";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final int TIMEOUT_MILLIS = 3000;
    private static final int MAX_ATTEMPTS = 2;

    private final RestClient restClient;

    @Autowired
    public FrankfurterFxRateProvider() {
        this(defaultRestClient());
    }

    FrankfurterFxRateProvider(RestClient restClient) {
        this.restClient = restClient;
    }

    private static RestClient defaultRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public Optional<FxQuote> rate(CurrencyCode from, CurrencyCode to, LocalDate on) {
        if (from == to) {
            return Optional.of(new FxQuote(from, to, on, BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP), FxSource.FRANKFURTER));
        }

        Optional<FrankfurterResponse> response = fetchWithRetry(on, from, to.name());
        return response
                .filter(r -> r.rates() != null && r.rates().containsKey(to.name()))
                .map(r -> new FxQuote(from, to, LocalDate.parse(r.date()),
                        r.rates().get(to.name()).setScale(8, RoundingMode.HALF_UP), FxSource.FRANKFURTER));
    }

    @Override
    public Map<CurrencyCode, BigDecimal> ratesFor(CurrencyCode base, LocalDate on) {
        String targets = java.util.Arrays.stream(CurrencyCode.values())
                .filter(c -> c != base)
                .map(Enum::name)
                .collect(Collectors.joining(","));

        Optional<FrankfurterResponse> response = fetchWithRetry(on, base, targets);
        Map<CurrencyCode, BigDecimal> result = new EnumMap<>(CurrencyCode.class);
        result.put(base, BigDecimal.ONE.setScale(8, RoundingMode.HALF_UP));
        response.ifPresent(r -> {
            if (r.rates() != null) {
                r.rates().forEach((code, rate) ->
                        CurrencyCode.fromDbValue(code).ifPresent(c -> result.put(c, rate.setScale(8, RoundingMode.HALF_UP))));
            }
        });
        return result;
    }

    @Override
    public String sourceName() {
        return FxSource.FRANKFURTER.name();
    }

    private Optional<FrankfurterResponse> fetchWithRetry(LocalDate date, CurrencyCode from, String to) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                FrankfurterResponse body = restClient.get()
                        .uri(RATE_URL, DATE_FORMAT.format(date), from.name(), to)
                        .retrieve()
                        .body(FrankfurterResponse.class);
                return Optional.ofNullable(body);
            } catch (RestClientException e) {
                log.warn("Frankfurter request for {}->{} on {} failed on attempt {}/{}: {}",
                        from, to, date, attempt, MAX_ATTEMPTS, e.getMessage());
            }
        }
        return Optional.empty();
    }
}
